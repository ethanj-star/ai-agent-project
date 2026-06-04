package com.travel.agent.ai.graph.model;

/**
 * 用户记忆类型。
 *
 * <p>系统架构位置：UserMemory -> <b>MemoryType</b> -> Planner / RequirementValidationNode</p>
 *
 * <p>职责：
 * <ul>
 *   <li>把用户记忆分成偏好、约束、事实、历史反馈和系统状态等可解释类别。</li>
 *   <li>帮助后续节点决定某条记忆是硬约束、软偏好，还是仅用于审计的历史信息。</li>
 * </ul>
 * </p>
 */
public enum MemoryType {

    /** 用户喜欢或不喜欢的旅行方式，例如“不住青旅”“偏好火车”。 */
    PREFERENCE,

    /** 更接近硬约束的信息，例如“不能吃海鲜”“必须轮椅友好”。 */
    CONSTRAINT,

    /** 相对稳定的事实，例如默认出发城市、常用人数或签证情况。 */
    FACT,

    /** 来自过往计划或版本反馈的历史信息。 */
    HISTORY,

    /** 系统为了流程恢复记录的辅助状态。 */
    SYSTEM
}
