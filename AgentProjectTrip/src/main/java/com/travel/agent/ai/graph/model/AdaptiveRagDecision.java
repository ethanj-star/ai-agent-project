package com.travel.agent.ai.graph.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Adaptive RAG 检索决策（Graph 层 - RAG Trace）。
 *
 * <p>系统架构位置：RagQueryClassifierAgent -> AdaptiveRagService -> <b>AdaptiveRagDecision</b> -> TravelPlanState</p>
 *
 * <p>职责：
 * <ul>
 *   <li>记录本次用户问题被归为哪种 RAG 查询类型。</li>
 *   <li>记录系统选择了哪种检索策略、哪些知识来源和哪些实际 query。</li>
 *   <li>在 Adaptive RAG 失败时标记 fallback，方便 Graph 回退旧 RetrieveKnowledgeNode。</li>
 * </ul>
 * </p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class AdaptiveRagDecision {

    /** 查询类型，决定后续是事实检索、探索检索、对比检索还是多阶段检索。 */
    private RagQueryType queryType = RagQueryType.FACT_BASED;

    /** 具体检索策略，决定如何构造 query、topK 和多阶段检索步骤。 */
    private RagRetrievalStrategy retrievalStrategy = RagRetrievalStrategy.PRECISE_KEYWORD;

    /** 本次策略计划使用的知识来源；第一版主要是需求表和 Pinecone，后续扩展 MySQL / 在线核查。 */
    private List<KnowledgeSourceType> sourceTypes = new ArrayList<>();

    /** 实际送入检索工具的 query 列表，用于调试 Adaptive RAG 是否真的改变了检索方式。 */
    private List<String> plannedQueries = new ArrayList<>();

    /** 每个 query 的 topK；第一版按策略统一设置，后续可按阶段分别调整。 */
    private int topK = 2;

    /** 人类可读解释，说明为什么选择该类型和策略。 */
    private String reason;

    /** 为 true 时表示 Adaptive RAG 不可用，Graph 应回退旧 RetrieveKnowledgeNode。 */
    private boolean fallbackRequired;

    /** fallback 的原因，例如分类失败、检索异常或依赖缺失。 */
    private String fallbackReason;

    public AdaptiveRagDecision() {
    }

    public static AdaptiveRagDecision fallback(String reason) {
        AdaptiveRagDecision decision = new AdaptiveRagDecision();
        decision.setFallbackRequired(true);
        decision.setFallbackReason(reason);
        decision.setReason(reason);
        return decision;
    }

    public RagQueryType getQueryType() {
        return queryType;
    }

    public void setQueryType(RagQueryType queryType) {
        this.queryType = queryType == null ? RagQueryType.FACT_BASED : queryType;
    }

    public RagRetrievalStrategy getRetrievalStrategy() {
        return retrievalStrategy;
    }

    public void setRetrievalStrategy(RagRetrievalStrategy retrievalStrategy) {
        this.retrievalStrategy = retrievalStrategy == null
                ? RagRetrievalStrategy.PRECISE_KEYWORD
                : retrievalStrategy;
    }

    public List<KnowledgeSourceType> getSourceTypes() {
        return sourceTypes;
    }

    public void setSourceTypes(List<KnowledgeSourceType> sourceTypes) {
        this.sourceTypes = sourceTypes == null ? new ArrayList<>() : new ArrayList<>(sourceTypes);
    }

    public List<String> getPlannedQueries() {
        return plannedQueries;
    }

    public void setPlannedQueries(List<String> plannedQueries) {
        this.plannedQueries = cleanList(plannedQueries);
    }

    public int getTopK() {
        return topK;
    }

    public void setTopK(int topK) {
        // topK 过大容易挤占 Planner prompt，第一版限制在 1-8 之间。
        this.topK = Math.min(8, Math.max(1, topK));
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = cleanText(reason);
    }

    public boolean isFallbackRequired() {
        return fallbackRequired;
    }

    public void setFallbackRequired(boolean fallbackRequired) {
        this.fallbackRequired = fallbackRequired;
    }

    public String getFallbackReason() {
        return fallbackReason;
    }

    public void setFallbackReason(String fallbackReason) {
        this.fallbackReason = cleanText(fallbackReason);
    }

    private static List<String> cleanList(List<String> values) {
        List<String> cleaned = new ArrayList<>();
        if (values == null) {
            return cleaned;
        }
        for (String value : values) {
            String cleanedValue = cleanText(value);
            if (cleanedValue != null) {
                cleaned.add(cleanedValue);
            }
        }
        return cleaned;
    }

    private static String cleanText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
