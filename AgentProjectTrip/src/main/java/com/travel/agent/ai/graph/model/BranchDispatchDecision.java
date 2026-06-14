package com.travel.agent.ai.graph.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 核心模型对分支任务派发的整体建议。
 *
 * <p>系统架构位置：ModelBranchDispatchNode -> <b>BranchDispatchDecision</b> -> BranchDispatchGuardNode</p>
 *
 * <p>职责：
 * <ul>
 *   <li>承载模型建议的多个 {@link BranchTaskSuggestion}。</li>
 *   <li>保存模型备注，说明哪些工具能力有限或暂未接入。</li>
 *   <li>在模型调用失败、解析失败或输出为空时标记需要回退旧规则派发。</li>
 * </ul>
 * </p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class BranchDispatchDecision {

    /** 模型建议的分支任务列表。 */
    private List<BranchTaskSuggestion> tasks = new ArrayList<>();

    /** 模型给调度器的备注，例如“未来天气工具暂不可用”。 */
    private List<String> notes = new ArrayList<>();

    /** 为 true 时，Guard 应跳过模型建议并回退旧 BranchDispatchNode。 */
    private boolean fallbackRequired;

    /** 触发 fallback 的原因，用于日志和后续 Trace。 */
    private String fallbackReason;

    public BranchDispatchDecision() {
    }

    public static BranchDispatchDecision fallback(String reason) {
        BranchDispatchDecision decision = new BranchDispatchDecision();
        decision.setFallbackRequired(true);
        decision.setFallbackReason(reason);
        return decision;
    }

    public List<BranchTaskSuggestion> getTasks() {
        return tasks;
    }

    public void setTasks(List<BranchTaskSuggestion> tasks) {
        this.tasks = tasks == null ? new ArrayList<>() : tasks;
    }

    public List<String> getNotes() {
        return notes;
    }

    public void setNotes(List<String> notes) {
        this.notes = cleanList(notes);
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
        if (values == null || values.isEmpty()) {
            return cleaned;
        }
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                cleaned.add(value.trim());
            }
        }
        return cleaned;
    }

    private static String cleanText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
