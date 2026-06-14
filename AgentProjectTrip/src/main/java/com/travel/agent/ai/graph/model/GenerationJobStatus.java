package com.travel.agent.ai.graph.model;

/**
 * 旅行方案生成任务状态枚举。
 *
 * <p>系统架构位置：RequirementController -> AsyncPlanGenerationService -> <b>GenerationJobStatus</b> -> GenerationJobStore</p>
 *
 * <p>职责：
 * <ul>
 *   <li>描述一次异步完整规划生成任务的生命周期。</li>
 *   <li>为前端轮询、重复点击保护、失败展示和后续任务恢复提供稳定状态值。</li>
 * </ul>
 * </p>
 */
public enum GenerationJobStatus {

    /** 任务已经创建，但后台线程尚未真正开始执行。 */
    PENDING,

    /** 后台线程正在执行校验、扣费、Graph 生成或保存计划。 */
    RUNNING,

    /** 任务已经成功完成，并且生成的旅行计划已经保存。 */
    SUCCEEDED,

    /** 任务执行失败，错误原因会写入 GenerationJob.errorMessage。 */
    FAILED,

    /** 用户或系统取消任务；第八阶段先预留状态，不实现主动取消。 */
    CANCELLED
}
