package com.travel.agent.ai.graph.store;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.WorkflowStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.Optional;

/**
 * JDBC 版会话 pending 状态仓库。
 *
 * <p>系统架构位置：LangGraphPlannerFacade -> ConversationStateStore -> <b>JdbcConversationStateStore</b> -> MySQL</p>
 *
 * <p>职责：
 * <ul>
 *   <li>保存第二阶段澄清循环中等待用户补充的 TravelPlanState。</li>
 *   <li>在用户下一轮回答时按 sessionId 恢复旧状态，让 Graph 可以续跑。</li>
 *   <li>任务完成后清理 pending 状态，避免普通聊天误续跑旧任务。</li>
 * </ul>
 * </p>
 */
@Repository
@ConditionalOnProperty(name = "travel.persistence.type", havingValue = "jdbc")
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcConversationStateStore implements ConversationStateStore {

    /** 没有显式 sessionId 时使用的开发期兜底会话。 */
    private static final String DEFAULT_SESSION_ID = "anonymous-session";

    /** 执行 MySQL 读写的 Spring JDBC 模板。 */
    private final JdbcTemplate jdbcTemplate;

    /** JSON 字段序列化辅助对象。 */
    private final JdbcJsonSupport jsonSupport;

    /**
     * 构造 JDBC 会话状态仓库。
     *
     * @param jdbcTemplate MySQL JDBC 模板
     * @param objectMapper Spring Boot 配置好的 JSON 解析器
     */
    public JdbcConversationStateStore(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.jsonSupport = new JdbcJsonSupport(objectMapper);
    }

    /**
     * 查找当前会话正在等待用户补充的工作流状态。
     *
     * @param sessionId 会话 ID
     * @return pending 状态；不存在或状态不是 NEEDS_CLARIFICATION 时返回 empty
     */
    @Override
    public Optional<TravelPlanState> findPendingState(String sessionId) {
        String normalizedSessionId = normalizeSessionId(sessionId);
        try {
            String json = jdbcTemplate.queryForObject(
                    "SELECT state_json FROM conversation_sessions WHERE session_id = ?",
                    String.class,
                    normalizedSessionId);
            TravelPlanState state = jsonSupport.fromJson(json, TravelPlanState.class);
            if (state == null || state.getWorkflowStatus() != WorkflowStatus.NEEDS_CLARIFICATION) {
                return Optional.empty();
            }
            return Optional.of(state);
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    /**
     * 保存需要用户补充信息的 pending 状态。
     *
     * <p>只保存 NEEDS_CLARIFICATION 状态，避免已完成任务或失败任务污染下一轮对话。</p>
     *
     * @param sessionId 会话 ID
     * @param state     当前工作流状态
     */
    @Override
    public void savePendingState(String sessionId, TravelPlanState state) {
        if (state == null || state.getWorkflowStatus() != WorkflowStatus.NEEDS_CLARIFICATION) {
            return;
        }
        String normalizedSessionId = normalizeSessionId(sessionId);
        String userId = normalizedSessionId;
        jdbcTemplate.update("""
                        INSERT INTO conversation_sessions
                          (session_id, user_id, current_requirement_id, state_json)
                        VALUES (?, ?, ?, ?)
                        ON DUPLICATE KEY UPDATE
                          user_id = VALUES(user_id),
                          current_requirement_id = VALUES(current_requirement_id),
                          state_json = VALUES(state_json),
                          updated_at = CURRENT_TIMESTAMP
                        """,
                normalizedSessionId,
                userId,
                state.getRequirementSpec() == null ? null : state.getRequirementSpec().getRequirementId(),
                jsonSupport.toJson(state));
    }

    /**
     * 清理当前会话的 pending 状态。
     *
     * @param sessionId 会话 ID
     */
    @Override
    public void clearPendingState(String sessionId) {
        jdbcTemplate.update("DELETE FROM conversation_sessions WHERE session_id = ?", normalizeSessionId(sessionId));
    }

    private static String normalizeSessionId(String sessionId) {
        return hasText(sessionId) ? sessionId.trim() : DEFAULT_SESSION_ID;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
