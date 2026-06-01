package com.travel.agent.ai.graph.model;

/**
 * 结构化旅行需求表状态枚举。
 *
 * <p>系统架构位置：RequirementController / RequirementExtractionAgent -> <b>RequirementStatus</b> -> GenerationGate</p>
 *
 * <p>职责：
 * <ul>
 *   <li>描述用户需求表从自然语言抽取、补全、确认到生成完成的生命周期。</li>
 *   <li>作为第五阶段生成门控的状态依据，避免信息不完整时误触发高成本规划流程。</li>
 * </ul>
 * </p>
 */
public enum RequirementStatus {

    /** 刚从自然语言抽取出来，尚未完成校验。 */
    DRAFT,

    /** 缺少阻塞字段，需要用户继续补充。 */
    NEEDS_USER_INPUT,

    /** 字段足够，可以展示给用户做最终确认。 */
    READY_TO_CONFIRM,

    /** 用户已确认需求表，可以进入扣费和完整生成。 */
    CONFIRMED,

    /** 完整规划正在生成中。 */
    GENERATING,

    /** 已经生成第一版完整规划。 */
    GENERATED,

    /** 用户取消或废弃当前需求表。 */
    CANCELLED
}
