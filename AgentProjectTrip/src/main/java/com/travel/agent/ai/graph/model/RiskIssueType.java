package com.travel.agent.ai.graph.model;

/**
 * 风险审查问题类型。
 *
 * <p>系统架构位置：TripRiskReasoningNode -> <b>RiskIssueType</b> -> PlanRevisionNode / FinalizeAnswerNode</p>
 *
 * <p>职责：
 * <ul>
 *   <li>限定第四阶段风险推理节点可以输出的问题类型。</li>
 *   <li>让自动修正节点根据机器可读类型生成稳定的 revision prompt。</li>
 *   <li>避免只依赖自然语言 message 判断是否需要修正。</li>
 * </ul>
 * </p>
 */
public enum RiskIssueType {

    /** 天气、季节或户外安排之间存在冲突。 */
    WEATHER_CONFLICT,

    /** 用户要求避开人多，但草案安排大量高人流景点或高峰时段。 */
    CROWD_CONFLICT,

    /** 预算估算和用户预算约束冲突。 */
    BUDGET_CONFLICT,

    /** 行程天数和用户给出的 durationDays 不匹配。 */
    DURATION_MISMATCH,

    /** 草案没有覆盖用户指定的全部目的地。 */
    DESTINATION_MISMATCH,

    /** 用户说不含国际机票，但草案预算中把国际机票算入了总额。 */
    FLIGHT_BUDGET_CONFLICT,

    /** 跨城交通、换乘或移动节奏存在明显风险。 */
    TRANSPORT_RISK,

    /** 单日安排过密，强度不合理。 */
    OVERLOADED_DAY,

    /** RAG 防坑信息没有被草案吸收，或知识库上下文不足。 */
    RAG_WARNING,

    /** 分支工具不可用，草案不应伪造对应实时数据。 */
    TOOL_UNAVAILABLE
}
