package com.travel.agent.ai.graph.model;

/**
 * Graph 工作流当前所处的阶段状态。
 *
 * <p>系统架构位置：TravelPlanState -> <b>WorkflowStatus</b> -> LangGraphPlannerFacade 分支判断</p>
 *
 * <p>职责：
 * <ul>
 *   <li>让 Validator、ClarifyQuestionNode 和 Facade 之间使用稳定枚举传递控制信号。</li>
 *   <li>区分“继续规划”“等待用户补充”“已经完成”和“流程失败”四类状态。</li>
 *   <li>为后续迁移到 LangGraph4j 条件边提供清晰的状态标签。</li>
 * </ul>
 * </p>
 */
public enum WorkflowStatus {

    /** 正在执行正常规划流程，后续可以继续进入 Planner、Validator 或 Finalizer。 */
    PLANNING,

    /** 当前信息不足，需要暂停工作流并向用户追问。 */
    NEEDS_CLARIFICATION,

    /** 当前任务已经生成最终答案，可以清理 pending 状态。 */
    COMPLETED,

    /** 当前任务执行失败，需要返回降级答案。 */
    FAILED
}
