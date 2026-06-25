package com.travel.agent.ai.graph.model;

/**
 * Adaptive RAG 查询类型（Graph 层 - RAG 策略入口分类）。
 *
 * <p>系统架构位置：AdaptiveRagNode -> RagQueryClassifierAgent -> <b>RagQueryType</b> -> AdaptiveRagService</p>
 *
 * <p>职责：描述用户当前问题“应该怎样检索知识库”。同样是 RAG，事实问答、灵感探索、
 * 城市比较、操作教程和多目的地行程规划需要不同检索策略，不能长期共用一次固定
 * similarity search。</p>
 */
public enum RagQueryType {

    /** 事实型问题，例如“欧洲有几个国家”“卢浮宫是什么类型的景点”。 */
    FACT_BASED,

    /** 探索推荐型问题，例如“法国有哪些小众海边城市”。 */
    EXPLORATORY,

    /** 比较型问题，例如“巴黎和尼斯哪个更适合亲子”。 */
    COMPARATIVE,

    /** 操作指导型问题，例如“怎么买博物馆门票”“申根签证怎么准备”。 */
    INSTRUCTIONAL,

    /** 多跳规划型问题，例如多国家、多天数、预算和交通组合的完整行程规划。 */
    MULTI_HOP
}
