package com.travel.agent.ai.graph.store;

import com.travel.agent.ai.graph.model.GenerationJob;

import java.util.List;
import java.util.Optional;

/**
 * 异步生成任务仓库接口。
 *
 * <p>系统架构位置：AsyncPlanGenerationService / GenerationJobController -> <b>GenerationJobStore</b> -> 内存 / MySQL</p>
 *
 * <p>职责：
 * <ul>
 *   <li>保存和查询第八阶段的异步完整规划生成任务。</li>
 *   <li>提供同一 requirementId 的运行中任务查询，避免重复点击导致重复扣费。</li>
 *   <li>隔离任务持久化实现，让后续接入队列或分布式 worker 时保持上层代码稳定。</li>
 * </ul>
 * </p>
 */
public interface GenerationJobStore {

    /**
     * 保存或覆盖一条生成任务记录。
     *
     * @param job 生成任务
     * @return 保存后的任务
     */
    GenerationJob save(GenerationJob job);

    /**
     * 按 jobId 查询任务。
     *
     * @param jobId 任务 ID
     * @return 找到时返回任务
     */
    Optional<GenerationJob> findById(String jobId);

    /**
     * 查询某张需求表当前是否已经有运行中的生成任务。
     *
     * @param requirementId 需求表 ID
     * @return 找到 PENDING 或 RUNNING 任务时返回
     */
    Optional<GenerationJob> findRunningByRequirementId(String requirementId);

    /**
     * 查询某个会话最近的生成任务。
     *
     * @param sessionId 会话 ID
     * @param limit     最大返回数量
     * @return 最近任务列表
     */
    List<GenerationJob> findRecentBySessionId(String sessionId, int limit);
}
