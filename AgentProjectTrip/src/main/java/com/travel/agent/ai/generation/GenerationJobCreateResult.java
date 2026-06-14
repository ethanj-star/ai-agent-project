package com.travel.agent.ai.generation;

import com.travel.agent.ai.graph.model.GenerationJob;

/**
 * 异步生成任务创建结果。
 *
 * <p>系统架构位置：AsyncPlanGenerationService -> <b>GenerationJobCreateResult</b> -> RequirementController</p>
 *
 * <p>职责：
 * <ul>
 *   <li>同时返回生成任务对象和“是否复用了已有运行中任务”的标记。</li>
 *   <li>让 Web 层可以给用户解释重复点击保护，而不需要直接访问 GenerationJobStore。</li>
 * </ul>
 * </p>
 *
 * @param job      新建或复用的生成任务
 * @param existing true 表示复用了已有 PENDING/RUNNING 任务
 */
public record GenerationJobCreateResult(GenerationJob job, boolean existing) {
}
