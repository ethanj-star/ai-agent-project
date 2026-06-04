package com.travel.agent.ai.graph.model;

/**
 * 用户记忆来源。
 *
 * <p>系统架构位置：MemoryController / UserMemoryService -> <b>MemorySource</b> -> UserMemory</p>
 *
 * <p>职责：
 * <ul>
 *   <li>记录记忆是用户明确表达、确认需求表、计划反馈还是系统事件产生的。</li>
 *   <li>为后续审计和删除策略提供依据，避免把模型猜测包装成用户事实。</li>
 * </ul>
 * </p>
 */
public enum MemorySource {

    /** 用户明确表达，例如“以后都不要给我推荐青旅”。 */
    USER_EXPLICIT,

    /** 用户确认过的结构化需求表。 */
    CONFIRMED_REQUIREMENT,

    /** 用户对某个计划版本的反馈。 */
    PLAN_FEEDBACK,

    /** 系统流程事件，例如当前 planId 或 pending job。 */
    SYSTEM_EVENT,

    /** 用户或开发者通过记忆管理接口手动写入。 */
    MANUAL
}
