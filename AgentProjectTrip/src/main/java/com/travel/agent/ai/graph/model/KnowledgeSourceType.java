package com.travel.agent.ai.graph.model;

/**
 * RAG 知识来源类型（Graph 层 - 检索来源审计）。
 *
 * <p>系统架构位置：AdaptiveRagService -> <b>KnowledgeSourceType</b> -> RagRetrievalResult / TravelPlanState</p>
 *
 * <p>职责：记录本次 RAG 上下文来自哪里。旅行规划会同时使用用户需求、私有攻略、
 * 景点主数据和后续联网事实核查；把来源显式记录下来，方便调试、前端展示和阶段 17 的 Eval。</p>
 */
public enum KnowledgeSourceType {

    /** 用户自然语言和前端确认后的结构化需求表。 */
    REQUIREMENT_SPEC,

    /** Pinecone 中的私有旅行攻略、游记和防坑知识。 */
    PINECONE_PRIVATE_GUIDE,

    /** 后续阶段维护在 MySQL 中的国家、城市和景点主数据。 */
    POI_CATALOG,

    /** 后续阶段沉淀的购票、签证、交通等结构化 FAQ。 */
    STRUCTURED_GUIDE,

    /** 后续阶段 OnlineFactCheckAgent 或联网搜索得到的强事实校验结果。 */
    ONLINE_FACT_CHECK
}
