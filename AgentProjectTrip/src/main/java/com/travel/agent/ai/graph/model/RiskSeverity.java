package com.travel.agent.ai.graph.model;

/**
 * 风险审查严重程度。
 *
 * <p>系统架构位置：TripRiskReasoningNode -> <b>RiskSeverity</b> -> RiskAssessment</p>
 *
 * <p>职责：
 * <ul>
 *   <li>区分风险是否会破坏用户核心需求。</li>
 *   <li>辅助 Facade 判断问题应该自动修正、追问用户，还是只在最终答案中提示。</li>
 *   <li>为后续更细的审查策略保留稳定枚举值。</li>
 * </ul>
 * </p>
 */
public enum RiskSeverity {

    /** 高风险：通常应该自动修正或追问用户。 */
    HIGH,

    /** 中风险：优先自动修正，无法修正时在最终答案中显式提示。 */
    MEDIUM,

    /** 低风险：主要作为最终答案的注意事项。 */
    LOW
}
