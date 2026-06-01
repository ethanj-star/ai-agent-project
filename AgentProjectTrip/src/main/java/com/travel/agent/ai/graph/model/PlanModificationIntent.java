package com.travel.agent.ai.graph.model;

/**
 * 计划修改意图枚举。
 *
 * <p>系统架构位置：PlanModificationAgent -> <b>PlanModificationIntent</b> -> PlanController</p>
 *
 * <p>职责：
 * <ul>
 *   <li>描述用户针对已有 planId 的自然语言修改请求属于哪种工程路径。</li>
 *   <li>区分局部行程 revision、核心需求变更、追问、普通评论和暂不支持的修改。</li>
 * </ul>
 * </p>
 */
public enum PlanModificationIntent {

    /** 只修改当前行程内容，不改变目的地、预算、天数等核心需求表字段。 */
    LOCAL_REVISION,

    /** 用户改变了预算、天数、目的地、人数、住宿偏好等核心需求，需要回到需求表确认。 */
    REQUIREMENT_CHANGE,

    /** 用户表达不清，系统需要先追问再修改。 */
    CLARIFICATION,

    /** 普通评论、感谢或闲聊，不生成新版本。 */
    DIRECT_COMMENT,

    /** 当前系统暂不支持的修改类型。 */
    UNSUPPORTED
}
