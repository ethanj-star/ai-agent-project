package com.travel.agent.ai.graph.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.ai.graph.model.MemoryScope;
import com.travel.agent.ai.graph.model.MemorySource;
import com.travel.agent.ai.graph.model.MemoryType;
import com.travel.agent.ai.graph.model.UserMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * JDBC 版用户记忆仓库。
 *
 * <p>系统架构位置：UserMemoryService -> UserMemoryStore -> <b>JdbcUserMemoryStore</b> -> MySQL user_memories</p>
 *
 * <p>职责：
 * <ul>
 *   <li>保存用户短期和长期记忆。</li>
 *   <li>按 userId / scope 读取 Planner 可用的 active 记忆。</li>
 *   <li>通过 active=false 软删除记忆，满足用户可控和后续审计需求。</li>
 * </ul>
 * </p>
 */
@Repository
@ConditionalOnProperty(name = "travel.persistence.type", havingValue = "jdbc")
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcUserMemoryStore implements UserMemoryStore {

    /** 执行 MySQL 读写的 Spring JDBC 模板。 */
    private final JdbcTemplate jdbcTemplate;

    /** JSON 字段序列化辅助对象。 */
    private final JdbcJsonSupport jsonSupport;

    /**
     * 构造 JDBC 用户记忆仓库。
     *
     * @param jdbcTemplate MySQL JDBC 模板
     * @param objectMapper Spring Boot 配置好的 JSON 解析器
     */
    public JdbcUserMemoryStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonSupport = new JdbcJsonSupport(objectMapper);
    }

    /**
     * 保存或覆盖用户记忆。
     *
     * @param memory 用户记忆
     * @return 保存后的用户记忆
     */
    @Override
    public UserMemory save(UserMemory memory) {
        if (memory == null || !hasText(memory.getMemoryId())) {
            throw new IllegalArgumentException("memoryId must not be blank");
        }
        if (!hasText(memory.getUserId())) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        jdbcTemplate.update("""
                        INSERT INTO user_memories
                          (memory_id, user_id, session_id, scope, type, memory_key, memory_value,
                           source, confidence, active, metadata_json)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          user_id = VALUES(user_id),
                          session_id = VALUES(session_id),
                          scope = VALUES(scope),
                          type = VALUES(type),
                          memory_key = VALUES(memory_key),
                          memory_value = VALUES(memory_value),
                          source = VALUES(source),
                          confidence = VALUES(confidence),
                          active = VALUES(active),
                          metadata_json = VALUES(metadata_json),
                          updated_at = CURRENT_TIMESTAMP
                        """,
                memory.getMemoryId(),
                memory.getUserId(),
                memory.getSessionId(),
                memory.getScope().name(),
                memory.getType().name(),
                memory.getKey(),
                memory.getValue(),
                memory.getSource().name(),
                memory.getConfidence(),
                memory.isActive(),
                jsonSupport.toJson(memory.getMetadata()));
        return memory;
    }

    /**
     * 根据记忆 ID 查询。
     *
     * @param memoryId 记忆 ID
     * @return 找到时返回记忆
     */
    @Override
    public Optional<UserMemory> findById(String memoryId) {
        if (!hasText(memoryId)) {
            return Optional.empty();
        }
        try {
            UserMemory memory = jdbcTemplate.queryForObject(
                    "SELECT * FROM user_memories WHERE memory_id = ?",
                    (rs, rowNum) -> mapMemory(rs),
                    memoryId);
            return Optional.ofNullable(memory);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 查询用户所有生效记忆。
     *
     * @param userId 用户 ID
     * @return 生效记忆列表
     */
    @Override
    public List<UserMemory> findActiveByUserId(String userId) {
        if (!hasText(userId)) {
            return List.of();
        }
        return jdbcTemplate.query("""
                        SELECT * FROM user_memories
                        WHERE user_id = ? AND active = TRUE
                        ORDER BY created_at ASC
                        """,
                (rs, rowNum) -> mapMemory(rs),
                userId);
    }

    /**
     * 查询用户指定作用域的生效记忆。
     *
     * @param userId 用户 ID
     * @param scope  记忆作用域
     * @return 生效记忆列表
     */
    @Override
    public List<UserMemory> findActiveByUserIdAndScope(String userId, MemoryScope scope) {
        if (!hasText(userId) || scope == null) {
            return List.of();
        }
        return jdbcTemplate.query("""
                        SELECT * FROM user_memories
                        WHERE user_id = ? AND scope = ? AND active = TRUE
                        ORDER BY created_at ASC
                        """,
                (rs, rowNum) -> mapMemory(rs),
                userId,
                scope.name());
    }

    /**
     * 禁用一条记忆。
     *
     * @param memoryId 记忆 ID
     */
    @Override
    public void deactivate(String memoryId) {
        if (hasText(memoryId)) {
            jdbcTemplate.update("""
                            UPDATE user_memories
                            SET active = FALSE,
                                updated_at = CURRENT_TIMESTAMP
                            WHERE memory_id = ?
                            """,
                    memoryId);
        }
    }

    private UserMemory mapMemory(ResultSet rs) throws java.sql.SQLException {
        UserMemory memory = new UserMemory();
        memory.setMemoryId(rs.getString("memory_id"));
        memory.setUserId(rs.getString("user_id"));
        memory.setSessionId(rs.getString("session_id"));
        memory.setScope(MemoryScope.valueOf(rs.getString("scope")));
        memory.setType(MemoryType.valueOf(rs.getString("type")));
        memory.setKey(rs.getString("memory_key"));
        memory.setValue(rs.getString("memory_value"));
        memory.setSource(MemorySource.valueOf(rs.getString("source")));
        memory.setConfidence(rs.getDouble("confidence"));
        memory.setActive(rs.getBoolean("active"));
        Map<String, Object> metadata = jsonSupport.fromJson(
                rs.getString("metadata_json"),
                new TypeReference<Map<String, Object>>() {
                });
        memory.setMetadata(metadata);
        memory.setCreatedAt(toInstant(rs.getTimestamp("created_at")));
        memory.setUpdatedAt(toInstant(rs.getTimestamp("updated_at")));
        return memory;
    }

    private static Instant toInstant(Timestamp timestamp) {
        return timestamp == null ? Instant.now() : timestamp.toInstant();
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
