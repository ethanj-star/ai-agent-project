package com.travel.agent.ai.graph.model;

/**
 * 旅行方案生成任务阶段枚举。
 *
 * <p>系统架构位置：AsyncPlanGenerationService -> <b>GenerationJobStage</b> -> 前端任务进度展示</p>
 *
 * <p>职责：
 * <ul>
 *   <li>用离散阶段描述长耗时生成过程，避免前端展示虚假的百分比进度。</li>
 *   <li>帮助开发者从日志和 generation_jobs 表中定位任务卡在哪一步。</li>
 * </ul>
 * </p>
 */
public enum GenerationJobStage {

    /** 任务记录已创建。 */
    CREATED,

    /** 正在检查结构化需求表是否满足完整生成条件。 */
    VALIDATING_REQUIREMENT,

    /** 正在扣除一次完整生成额度。 */
    CHARGING_CREDIT,

    /** 正在执行 LangGraph 规划工作流。 */
    RUNNING_GRAPH,

    /** 正在把成功生成的旅行计划保存到 TravelPlanStore。 */
    SAVING_PLAN,

    /** 生成失败且已经扣费，正在退回额度。 */
    REFUNDING_CREDIT,

    /** 任务已经进入终态。 */
    FINISHED
}
