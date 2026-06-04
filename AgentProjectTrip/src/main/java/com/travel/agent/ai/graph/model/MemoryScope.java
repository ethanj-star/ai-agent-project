package com.travel.agent.ai.graph.model;

/**
 * 用户记忆作用域。
 *
 * <p>系统架构位置：MemoryController / UserMemoryService -> <b>MemoryScope</b> -> UserMemoryStore</p>
 *
 * <p>职责：
 * <ul>
 *   <li>区分只对当前会话有效的短期记忆和跨旅行复用的长期记忆。</li>
 *   <li>让 Planner 读取记忆时可以按作用域控制优先级和提示词表达。</li>
 * </ul>
 * </p>
 */
public enum MemoryScope {

    /** 当前会话或当前 plan 有效，通常来自确认需求表或本次修改反馈。 */
    SHORT_TERM,

    /** 跨会话长期有效，通常来自用户明确表达或多次确认的稳定偏好。 */
    LONG_TERM
}
