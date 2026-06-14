package com.travel.agent.ai.graph.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.ai.graph.model.PlannerDraft;
import com.travel.agent.ai.graph.model.RiskAssessment;
import com.travel.agent.ai.graph.model.TravelPlanRecord;
import com.travel.agent.ai.graph.model.TravelPlanVersion;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import com.travel.agent.ai.graph.model.ValidationIssue;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JDBC 版旅行计划版本仓库。
 *
 * <p>系统架构位置：RequirementController / PlanController -> TravelPlanStore -> <b>JdbcTravelPlanStore</b> -> MySQL</p>
 *
 * <p>职责：
 * <ul>
 *   <li>把第六阶段的 TravelPlanRecord 保存到 travel_plans 表。</li>
 *   <li>把每次生成或修改后的 TravelPlanVersion 保存到 travel_plan_versions 表。</li>
 *   <li>通过事务保证计划主记录和版本记录的一致性。</li>
 * </ul>
 * </p>
 */
@Repository
@ConditionalOnProperty(name = "travel.persistence.type", havingValue = "jdbc")
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcTravelPlanStore implements TravelPlanStore {

    /** 没有显式 sessionId 时使用的开发期兜底会话。 */
    private static final String DEFAULT_SESSION_ID = "anonymous-session";

    /** 执行 MySQL 读写的 Spring JDBC 模板。 */
    private final JdbcTemplate jdbcTemplate;

    /** JSON 字段序列化辅助对象。 */
    private final JdbcJsonSupport jsonSupport;

    /**
     * 构造 JDBC 计划仓库。
     *
     * @param jdbcTemplate MySQL JDBC 模板
     * @param objectMapper Spring Boot 配置好的 JSON 解析器
     */
    public JdbcTravelPlanStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonSupport = new JdbcJsonSupport(objectMapper);
    }

    /**
     * 保存或覆盖计划记录。
     *
     * <p>处理流程：
     * <ol>
     *   <li>upsert 计划主记录。</li>
     *   <li>删除旧版本快照并重新写入当前对象中的 versions。</li>
     *   <li>使用事务避免主记录和版本列表只写入一半。</li>
     * </ol>
     * </p>
     *
     * @param record 计划主记录
     * @return 保存后的计划记录
     */
    @Override
    @Transactional
    public TravelPlanRecord save(TravelPlanRecord record) {
        if (record == null || !hasText(record.getPlanId())) {
            throw new IllegalArgumentException("planId must not be blank");
        }
        String sessionId = normalizeSessionId(record.getSessionId());
        record.setSessionId(sessionId);
        String userId = sessionId;

        // 主表只保存当前版本号和需求表快照；完整版本内容写入 travel_plan_versions。
        jdbcTemplate.update("""
                        INSERT INTO travel_plans
                          (plan_id, requirement_id, session_id, user_id, current_version, requirement_spec_json)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          requirement_id = VALUES(requirement_id),
                          session_id = VALUES(session_id),
                          user_id = VALUES(user_id),
                          current_version = VALUES(current_version),
                          requirement_spec_json = VALUES(requirement_spec_json),
                          updated_at = CURRENT_TIMESTAMP
                        """,
                record.getPlanId(),
                record.getRequirementId(),
                sessionId,
                userId,
                record.getCurrentVersion(),
                jsonSupport.toJson(record.getRequirementSpec()));

        // save(record) 语义是“用当前对象快照覆盖数据库”，所以先删除旧版本再重写。
        jdbcTemplate.update("DELETE FROM travel_plan_versions WHERE plan_id = ?", record.getPlanId());
        if (record.getVersions() != null) {
            for (TravelPlanVersion version : record.getVersions()) {
                insertOrUpdateVersion(record.getPlanId(), version);
            }
        }
        return record;
    }

    /**
     * 按 planId 查询计划。
     *
     * @param planId 计划 ID
     * @return 找到时返回计划记录
     */
    @Override
    public Optional<TravelPlanRecord> findById(String planId) {
        if (!hasText(planId)) {
            return Optional.empty();
        }
        try {
            TravelPlanRecord record = jdbcTemplate.queryForObject(
                    "SELECT * FROM travel_plans WHERE plan_id = ?",
                    (rs, rowNum) -> mapRecord(rs),
                    planId);
            if (record == null) {
                return Optional.empty();
            }
            // 主记录和版本表分开存储，读取时重新组装为领域对象。
            record.setVersions(findVersions(planId));
            return Optional.of(record);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 给计划追加一个新版本。
     *
     * <p>数据库实现会同时写入版本表并更新主记录 current_version。</p>
     *
     * @param planId  计划 ID
     * @param version 新版本
     * @return 更新后的计划记录
     */
    @Override
    @Transactional
    public Optional<TravelPlanRecord> addVersion(String planId, TravelPlanVersion version) {
        if (!hasText(planId) || version == null) {
            return Optional.empty();
        }
        if (findById(planId).isEmpty()) {
            return Optional.empty();
        }
        // 追加版本不重写所有历史版本，只 upsert 当前新版本并推进 current_version。
        insertOrUpdateVersion(planId, version);
        jdbcTemplate.update("""
                        UPDATE travel_plans
                        SET current_version = GREATEST(current_version, ?),
                            updated_at = CURRENT_TIMESTAMP
                        WHERE plan_id = ?
                        """,
                version.getVersion(),
                planId);
        return findById(planId);
    }

    /**
     * 查询计划指定版本。
     *
     * @param planId        计划 ID
     * @param versionNumber 版本号
     * @return 找到时返回版本记录
     */
    @Override
    public Optional<TravelPlanVersion> findVersion(String planId, int versionNumber) {
        if (!hasText(planId)) {
            return Optional.empty();
        }
        try {
            TravelPlanVersion version = jdbcTemplate.queryForObject(
                    "SELECT * FROM travel_plan_versions WHERE plan_id = ? AND version = ?",
                    (rs, rowNum) -> mapVersion(rs),
                    planId,
                    versionNumber);
            return Optional.ofNullable(version);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    private List<TravelPlanVersion> findVersions(String planId) {
        // 版本按升序返回，TravelPlanRecord.current() 会基于 currentVersion 找最新版本。
        return jdbcTemplate.query(
                "SELECT * FROM travel_plan_versions WHERE plan_id = ? ORDER BY version ASC",
                (rs, rowNum) -> mapVersion(rs),
                planId);
    }

    private void insertOrUpdateVersion(String planId, TravelPlanVersion version) {
        if (version == null) {
            return;
        }
        // 版本里的 draft/risk/validationIssues 结构变化频繁，使用 JSON 列减少表结构变更。
        jdbcTemplate.update("""
                        INSERT INTO travel_plan_versions
                          (plan_id, version, final_answer, draft_json, risk_assessment_json,
                           validation_issues_json, modification_summary, user_instruction)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          final_answer = VALUES(final_answer),
                          draft_json = VALUES(draft_json),
                          risk_assessment_json = VALUES(risk_assessment_json),
                          validation_issues_json = VALUES(validation_issues_json),
                          modification_summary = VALUES(modification_summary),
                          user_instruction = VALUES(user_instruction)
                        """,
                planId,
                version.getVersion(),
                version.getFinalAnswer(),
                jsonSupport.toJson(version.getDraft()),
                jsonSupport.toJson(version.getRiskAssessment()),
                jsonSupport.toJson(version.getValidationIssues()),
                version.getModificationSummary(),
                version.getUserInstruction());
    }

    private TravelPlanRecord mapRecord(ResultSet rs) throws java.sql.SQLException {
        // 只映射主表字段，版本列表由 findVersions 单独补齐。
        TravelPlanRecord record = new TravelPlanRecord();
        record.setPlanId(rs.getString("plan_id"));
        record.setRequirementId(rs.getString("requirement_id"));
        record.setSessionId(rs.getString("session_id"));
        record.setCurrentVersion(rs.getInt("current_version"));
        record.setRequirementSpec(jsonSupport.fromJson(
                rs.getString("requirement_spec_json"),
                TravelRequirementSpec.class));
        record.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        record.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
        return record;
    }

    private TravelPlanVersion mapVersion(ResultSet rs) throws java.sql.SQLException {
        // 单个版本包含最终答案和生成时的审查快照，用于后续版本对比和回滚。
        TravelPlanVersion version = new TravelPlanVersion();
        version.setVersion(rs.getInt("version"));
        version.setFinalAnswer(rs.getString("final_answer"));
        version.setDraft(jsonSupport.fromJson(rs.getString("draft_json"), PlannerDraft.class));
        version.setRiskAssessment(jsonSupport.fromJson(rs.getString("risk_assessment_json"), RiskAssessment.class));
        List<ValidationIssue> issues = jsonSupport.fromJson(
                rs.getString("validation_issues_json"),
                new TypeReference<List<ValidationIssue>>() {
                });
        version.setValidationIssues(issues);
        version.setModificationSummary(rs.getString("modification_summary"));
        version.setUserInstruction(rs.getString("user_instruction"));
        version.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        return version;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? Instant.now() : timestamp.toInstant();
    }

    private static String normalizeSessionId(String sessionId) {
        // 开发期没有登录态时，用固定 sessionId 保证数据库字段和查询 key 稳定。
        return hasText(sessionId) ? sessionId.trim() : DEFAULT_SESSION_ID;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
