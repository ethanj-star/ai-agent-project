package com.travel.agent.ai.graph.model;

/**
 * Adaptive RAG 检索策略（Graph 层 - 知识库调用方式）。
 *
 * <p>系统架构位置：RagQueryType -> <b>RagRetrievalStrategy</b> -> AdaptiveRagService -> KnowledgeTools / Pinecone</p>
 *
 * <p>职责：把抽象查询类型落成具体检索动作。第一版先复用现有 Pinecone 检索能力，
 * 通过不同 query 构造、topK 和多次检索实现策略差异；后续可在这里继续接入 reranker、
 * metadata filter、结构化 FAQ 和官方来源。</p>
 */
public enum RagRetrievalStrategy {

    /** 精确关键词检索，适合事实型短问题。 */
    PRECISE_KEYWORD,

    /** 语义扩展检索，适合小众推荐、灵感探索和风格化攻略召回。 */
    SEMANTIC_EXPANSION,

    /** 多对象对比检索，适合城市、景点或国家之间的横向比较。 */
    COMPARATIVE_MULTI_SOURCE,

    /** 结构化攻略优先，适合签证、购票、交通教程等步骤型问题。 */
    STRUCTURED_GUIDE_FIRST,

    /** 多阶段上下文检索，适合完整行程规划和多条件组合推理。 */
    MULTI_STAGE_CONTEXT
}
