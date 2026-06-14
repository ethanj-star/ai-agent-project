package com.travel.agent.ai.graph.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * JDBC 版结构化旅行需求表仓库。
 *
 * <p>系统架构位置：RequirementController -> RequirementStore -> <b>JdbcRequirementStore</b> -> MySQL</p>
 *
 * <p>职责：
 * <ul>
 *   <li>把第五阶段的 {@link TravelRequirementSpec} 持久化到 travel_requirements 表。</li>
 *   <li>用 JSON 快照保存完整需求表，避免早期字段频繁变化导致表结构震荡。</li>
 *   <li>保持 RequirementStore 接口不变，让 Controller 和 Agent 无感切换内存/数据库实现。</li>
 * </ul>
 * </p>
 */
@Repository
@ConditionalOnProperty(name = "travel.persistence.type", havingValue = "jdbc")
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcRequirementStore implements RequirementStore {

    /** 没有显式 sessionId 时使用的开发期兜底会话。 */
    private static final String DEFAULT_SESSION_ID = "anonymous-session";

    /** 执行 MySQL 读写的 Spring JDBC 模板。 */
    private final JdbcTemplate jdbcTemplate;

    /** JSON 字段序列化辅助对象。 */
    private final JdbcJsonSupport jsonSupport;

    /**
     * 构造 JDBC 需求表仓库。
     *
     * @param jdbcTemplate MySQL JDBC 模板
     * @param objectMapper Spring Boot 配置好的 JSON 解析器
     */
    public JdbcRequirementStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonSupport = new JdbcJsonSupport(objectMapper);
    }

    /**
     * 保存或覆盖需求表。
     *
     * <p>处理流程：
     * <ol>
     *   <li>校验 requirementId，避免无法索引的数据进入数据库。</li>
     *   <li>将完整 TravelRequirementSpec 序列化为 spec_json。</li>
     *   <li>使用 MySQL upsert 保证 draft/update/confirm 都可以复用同一个入口。</li>
     * </ol>
     * </p>
     *
     * @param spec 结构化旅行需求表
     * @return 保存后的需求表
     */
    @Override
    public TravelRequirementSpec save(TravelRequirementSpec spec) {
        if (spec == null || !hasText(spec.getRequirementId())) {
            throw new IllegalArgumentException("requirementId must not be blank");
        }
        String sessionId = normalizeSessionId(spec.getSessionId());
        spec.setSessionId(sessionId);
        String userId = sessionId;
        // spec_json 保存完整需求表快照，普通列只保留查询和展示最常用字段。
        String specJson = jsonSupport.toJson(spec);

        // draft、update、confirm 都通过同一个 save 入口，因此用 upsert 覆盖最新快照。
        jdbcTemplate.update("""
                        INSERT INTO travel_requirements
                          (requirement_id, session_id, user_id, status, original_message, spec_json)
                        VALUES (?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          session_id = VALUES(session_id),
                          user_id = VALUES(user_id),
                          status = VALUES(status),
                          original_message = VALUES(original_message),
                          spec_json = VALUES(spec_json),
                          updated_at = CURRENT_TIMESTAMP
                        """,
                spec.getRequirementId(),
                sessionId,
                userId,
                spec.getStatus().name(),
                spec.getOriginalMessage(),
                specJson);
        return spec;
    }

    /**
     * 根据 requirementId 查询需求表。
     *
     * @param requirementId 需求表 ID
     * @return 找到时返回需求表，否则返回 empty
     */
    @Override
    public Optional<TravelRequirementSpec> findById(String requirementId) {
        if (!hasText(requirementId)) {
            return Optional.empty();
        }
        try {
            String json = jdbcTemplate.queryForObject(
                    "SELECT spec_json FROM travel_requirements WHERE requirement_id = ?",
                    String.class,
                    requirementId);
            return Optional.ofNullable(jsonSupport.fromJson(json, TravelRequirementSpec.class));
        } catch (EmptyResultDataAccessException e) {
            // 找不到需求表是正常业务分支，Controller 会把 empty 映射成 404。
            return Optional.empty();
        }
    }

    /**
     * 删除需求表。
     *
     * <p>当前接口用于开发期清理；正式产品中可以改为软删除或状态归档。</p>
     *
     * @param requirementId 需求表 ID
     */
    @Override
    public void delete(String requirementId) {
        if (hasText(requirementId)) {
            jdbcTemplate.update("DELETE FROM travel_requirements WHERE requirement_id = ?", requirementId);
        }
    }

    private static String normalizeSessionId(String sessionId) {
        // 开发期可能没有显式 sessionId，用固定值保证数据库非空字段和查询 key 稳定。
        return hasText(sessionId) ? sessionId.trim() : DEFAULT_SESSION_ID;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
