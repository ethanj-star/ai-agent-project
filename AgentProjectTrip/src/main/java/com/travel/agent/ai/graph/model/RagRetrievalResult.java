package com.travel.agent.ai.graph.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Adaptive RAG 检索结果（Graph 层 - RAG 上下文输出）。
 *
 * <p>系统架构位置：AdaptiveRagService -> <b>RagRetrievalResult</b> -> AdaptiveRagNode -> TravelPlanState</p>
 *
 * <p>职责：
 * <ul>
 *   <li>保存最终给 Planner 使用的 RAG 上下文文本。</li>
 *   <li>记录实际执行过的检索 query、命中文档数量和来源摘要。</li>
 *   <li>为后续前端调试面板和 Agent Eval 提供可观测信息。</li>
 * </ul>
 * </p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RagRetrievalResult {

    /** 本次检索对应的 Adaptive RAG 决策。 */
    private AdaptiveRagDecision decision;

    /** 最终写入 TravelPlanState.ragContext 的上下文文本。 */
    private String context;

    /** 实际执行的 query，可能比 plannedQueries 少，例如异常时提前 fallback。 */
    private List<String> executedQueries = new ArrayList<>();

    /** 命中文档数量；第一版来自 Pinecone 返回的 Document 数量。 */
    private int hitCount;

    /** 来源摘要，例如“Pinecone: 3 docs”。 */
    private List<String> sourceSummaries = new ArrayList<>();

    /** 为 true 时表示本次结果来自兜底逻辑，而不是完整 Adaptive RAG。 */
    private boolean fallbackUsed;

    public RagRetrievalResult() {
    }

    public static RagRetrievalResult fallback(AdaptiveRagDecision decision, String context) {
        RagRetrievalResult result = new RagRetrievalResult();
        result.setDecision(decision);
        result.setContext(context);
        result.setFallbackUsed(true);
        return result;
    }

    public AdaptiveRagDecision getDecision() {
        return decision;
    }

    public void setDecision(AdaptiveRagDecision decision) {
        this.decision = decision;
    }

    public String getContext() {
        return context;
    }

    public void setContext(String context) {
        this.context = cleanText(context);
    }

    public List<String> getExecutedQueries() {
        return executedQueries;
    }

    public void setExecutedQueries(List<String> executedQueries) {
        this.executedQueries = cleanList(executedQueries);
    }

    public int getHitCount() {
        return hitCount;
    }

    public void setHitCount(int hitCount) {
        this.hitCount = Math.max(0, hitCount);
    }

    public List<String> getSourceSummaries() {
        return sourceSummaries;
    }

    public void setSourceSummaries(List<String> sourceSummaries) {
        this.sourceSummaries = cleanList(sourceSummaries);
    }

    public boolean isFallbackUsed() {
        return fallbackUsed;
    }

    public void setFallbackUsed(boolean fallbackUsed) {
        this.fallbackUsed = fallbackUsed;
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
