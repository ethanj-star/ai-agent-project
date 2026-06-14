package com.travel.agent.ai.graph.store;

import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.WorkflowStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 基于内存 Map 的会话 pending 状态仓库。
 *
 * <p>系统架构位置：LangGraphPlannerFacade -> ConversationStateStore -> <b>InMemoryConversationStateStore</b></p>
 *
 * <p>职责：
 * <ul>
 *   <li>为第二阶段澄清循环提供最轻量的状态保存能力。</li>
 *   <li>只保存 {@link WorkflowStatus#NEEDS_CLARIFICATION} 状态，避免缓存已完成或失败任务。</li>
 *   <li>用 {@link ConcurrentHashMap} 支持 Web 请求并发读写。</li>
 * </ul>
 * </p>
 *
 * <p>注意：本实现重启后状态会丢失。后续阶段可以在不改变接口的前提下替换为 Redis 或数据库实现。</p>
 */
@Component
@ConditionalOnProperty(name = "travel.persistence.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryConversationStateStore implements ConversationStateStore {

    /** sessionId -> pending TravelPlanState。 */
    private final Map<String, TravelPlanState> states = new ConcurrentHashMap<>();

    @Override
    public Optional<TravelPlanState> findPendingState(String sessionId) {
        if (!hasText(sessionId)) {
            return Optional.empty();
        }
        TravelPlanState state = states.get(sessionId);
        // 仓库只对外暴露“等待用户补充”的状态；其他状态即使存在也不允许续跑。
        if (state == null || state.getWorkflowStatus() != WorkflowStatus.NEEDS_CLARIFICATION) {
            return Optional.empty();
        }
        return Optional.of(state);
    }

    @Override
    public void savePendingState(String sessionId, TravelPlanState state) {
        if (!hasText(sessionId) || state == null) {
            return;
        }
        if (state.getWorkflowStatus() == WorkflowStatus.NEEDS_CLARIFICATION) {
            // 同一个 session 只保留最新 pending 任务，用户下一轮回答会续跑这份状态。
            states.put(sessionId, state);
        }
    }

    @Override
    public void clearPendingState(String sessionId) {
        if (hasText(sessionId)) {
            // 最终答案生成后必须清理，否则下一次普通输入会被误判为上一轮澄清回答。
            states.remove(sessionId);
        }
    }

    /**
     * 判断字符串是否包含非空白文本。
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
