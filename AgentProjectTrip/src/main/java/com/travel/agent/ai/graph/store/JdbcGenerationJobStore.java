package com.travel.agent.ai.graph.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.ai.graph.model.GenerationJob;
import com.travel.agent.ai.graph.model.GenerationJobStage;
import com.travel.agent.ai.graph.model.GenerationJobStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC 版异步生成任务仓库。
 *
 * <p>系统架构位置：AsyncPlanGenerationService / GenerationJobController -> GenerationJobStore -> <b>JdbcGenerationJobStore</b> -> MySQL</p>
 *
 * <p>职责：
 * <ul>
 *   <li>把第八阶段的 GenerationJob 生命周期写入 generation_jobs 表。</li>
 *   <li>支持前端轮询查询任务状态和任务结束后的 planId。</li>
 *   <li>通过 findRunningByRequirementId 为后端重复点击保护提供持久化判断。</li>
 * </ul>
 * </p>
 */
@Repository
@ConditionalOnProperty(name = "travel.persistence.type", havingValue = "jdbc")
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcGenerationJobStore implements GenerationJobStore {

    /** 执行 MySQL 读写的 Spring JDBC 模板。 */
    private final JdbcTemplate jdbcTemplate;

    /** JSON 字段序列化辅助对象。 */
    private final JdbcJsonSupport jsonSupport;

    /**
     * 构造 JDBC 任务仓库。
     *
     * @param jdbcTemplate MySQL JDBC 模板
     * @param objectMapper Spring Boot 配置好的 JSON 解析器
     */
    public JdbcGenerationJobStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonSupport = new JdbcJsonSupport(objectMapper);
    }

    /**
     * 保存或覆盖一条任务记录。
     *
     * <p>处理流程：
     * <ol>
     *   <li>按 job_id upsert generation_jobs 主表。</li>
     *   <li>request/result 以 JSON 写入，避免为了调试信息频繁改表。</li>
     *   <li>终态任务写入 finished_at，非终态保持为空。</li>
     * </ol>
     * </p>
     *
     * @param job 生成任务
     * @return 保存后的任务
     */
    @Override
    public GenerationJob save(GenerationJob job) {
        if (job == null || !hasText(job.getJobId())) {
            throw new IllegalArgumentException("jobId must not be blank");
        }
        // 异步任务会多次更新状态和阶段，同一个 job_id 使用 upsert 覆盖最新快照。
        jdbcTemplate.update("""
                        INSERT INTO generation_jobs
                          (job_id, user_id, session_id, requirement_id, plan_id, status, current_stage,
                           request_json, result_json, error_message, credit_charged, finished_at)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          user_id = VALUES(user_id),
                          session_id = VALUES(session_id),
                          requirement_id = VALUES(requirement_id),
                          plan_id = VALUES(plan_id),
                          status = VALUES(status),
                          current_stage = VALUES(current_stage),
                          request_json = VALUES(request_json),
                          result_json = VALUES(result_json),
                          error_message = VALUES(error_message),
                          credit_charged = VALUES(credit_charged),
                          finished_at = VALUES(finished_at),
                          updated_at = CURRENT_TIMESTAMP
                        """,
                job.getJobId(),
                job.getUserId(),
                job.getSessionId(),
                job.getRequirementId(),
                job.getPlanId(),
                job.getStatus().name(),
                job.getCurrentStage().name(),
                jsonSupport.toJson(job.getRequest()),
                jsonSupport.toJson(job.getResult()),
                job.getErrorMessage(),
                job.isCreditCharged(),
                toTimestamp(job.getFinishedAt()));
        return job;
    }

    /**
     * 按 jobId 查询任务。
     *
     * @param jobId 任务 ID
     * @return 找到时返回任务
     */
    @Override
    public Optional<GenerationJob> findById(String jobId) {
        if (!hasText(jobId)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    "SELECT * FROM generation_jobs WHERE job_id = ?",
                    (rs, rowNum) -> mapJob(rs),
                    jobId));
        } catch (EmptyResultDataAccessException e) {
            // jobId 不存在是轮询接口的正常 404 分支。
            return Optional.empty();
        }
    }

    /**
     * 查询同一需求表的运行中任务。
     *
     * @param requirementId 需求表 ID
     * @return 找到 PENDING 或 RUNNING 任务时返回
     */
    @Override
    public Optional<GenerationJob> findRunningByRequirementId(String requirementId) {
        if (!hasText(requirementId)) {
            return Optional.empty();
        }
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                            SELECT * FROM generation_jobs
                            WHERE requirement_id = ? AND status IN ('PENDING', 'RUNNING')
                            ORDER BY created_at ASC
                            LIMIT 1
                            """,
                    (rs, rowNum) -> mapJob(rs),
                    requirementId));
        } catch (EmptyResultDataAccessException e) {
            // 没有运行中任务表示可以创建新任务。
            return Optional.empty();
        }
    }

    /**
     * 查询某个会话最近的任务。
     *
     * @param sessionId 会话 ID
     * @param limit     最大返回数量
     * @return 最近任务列表
     */
    @Override
    public List<GenerationJob> findRecentBySessionId(String sessionId, int limit) {
        if (!hasText(sessionId) || limit <= 0) {
            return List.of();
        }
        return jdbcTemplate.query("""
                        SELECT * FROM generation_jobs
                        WHERE session_id = ?
                        ORDER BY created_at DESC
                        LIMIT ?
                        """,
                (rs, rowNum) -> mapJob(rs),
                sessionId,
                limit);
    }

    private GenerationJob mapJob(ResultSet rs) throws java.sql.SQLException {
        // 数据库列到领域对象的唯一映射点，Controller 和 Service 不需要关心表结构。
        GenerationJob job = new GenerationJob();
        job.setJobId(rs.getString("job_id"));
        job.setUserId(rs.getString("user_id"));
        job.setSessionId(rs.getString("session_id"));
        job.setRequirementId(rs.getString("requirement_id"));
        job.setPlanId(rs.getString("plan_id"));
        job.setStatus(parseStatus(rs.getString("status")));
        job.setCurrentStage(parseStage(rs.getString("current_stage")));
        job.setRequest(readMap(rs.getString("request_json")));
        job.setResult(readMap(rs.getString("result_json")));
        job.setErrorMessage(rs.getString("error_message"));
        job.setCreditCharged(rs.getBoolean("credit_charged"));
        job.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        job.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
        job.setFinishedAt(toInstantOrNull(rs.getTimestamp("finished_at")));
        return job;
    }

    private Map<String, Object> readMap(String json) {
        Map<String, Object> value = jsonSupport.fromJson(
                json,
                new TypeReference<Map<String, Object>>() {
                });
        // request/result 允许为空；对外统一返回空 Map，前端不用判 null。
        return value == null ? new LinkedHashMap<>() : value;
    }

    private static GenerationJobStatus parseStatus(String value) {
        if (!hasText(value)) {
            return GenerationJobStatus.PENDING;
        }
        try {
            return GenerationJobStatus.valueOf(value);
        } catch (IllegalArgumentException e) {
            // 数据库出现未知状态时按 FAILED 处理，避免前端无限轮询不可识别状态。
            return GenerationJobStatus.FAILED;
        }
    }

    private static GenerationJobStage parseStage(String value) {
        if (!hasText(value)) {
            return GenerationJobStage.CREATED;
        }
        try {
            return GenerationJobStage.valueOf(value);
        } catch (IllegalArgumentException e) {
            // 未知阶段说明数据和代码版本不匹配，按 FINISHED 兜底让任务不再表现为进行中。
            return GenerationJobStage.FINISHED;
        }
    }

    private static Timestamp toTimestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant toInstant(Timestamp timestamp) {
        // created_at / updated_at 理论上由数据库填充；为空时用 now 保持 DTO 非空。
        return timestamp == null ? Instant.now() : timestamp.toInstant();
    }

    private static Instant toInstantOrNull(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
