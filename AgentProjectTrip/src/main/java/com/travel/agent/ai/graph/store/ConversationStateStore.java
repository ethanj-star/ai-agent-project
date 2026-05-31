package com.travel.agent.ai.graph.store;

import com.travel.agent.ai.graph.model.TravelPlanState;

import java.util.Optional;

/**
 * 会话级 pending 工作流状态仓库。
 *
 * <p>系统架构位置：LangGraphPlannerFacade -> <b>ConversationStateStore</b> -> TravelPlanState</p>
 *
 * <p>职责：
 * <ul>
 *   <li>在系统需要追问用户时保存当前工作流状态。</li>
 *   <li>在用户下一轮补充信息时取回旧状态，让 Graph 可以续跑原任务。</li>
 *   <li>在任务完成后清理 pending 状态，避免后续普通聊天误续跑旧任务。</li>
 * </ul>
 * </p>
 */
public interface ConversationStateStore {

    /**
     * 查找当前会话正在等待用户补充的工作流状态。
     *
     * @param sessionId 会话 ID
     * @return pending 状态；不存在时返回 empty
     */
    Optional<TravelPlanState> findPendingState(String sessionId);

    /**
     * 保存需要用户补充信息的 pending 状态。
     *
     * @param sessionId 会话 ID
     * @param state     当前工作流状态
     */
    void savePendingState(String sessionId, TravelPlanState state);

    /**
     * 清理当前会话的 pending 状态。
     *
     * @param sessionId 会话 ID
     */
    void clearPendingState(String sessionId);
}
