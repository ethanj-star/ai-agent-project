package com.travel.agent.ai.graph.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * Planner 节点生成的结构化旅行草案（Graph 层 - LLM 输出协议）。
 *
 * <p>系统架构位置：PlanDraftNode → <b>PlannerDraft</b> → ValidateDraftNode / FinalizeAnswerNode</p>
 *
 * <p>职责：
 * <ul>
 *   <li>承载核心模型生成的第一版行程规划草案。</li>
 *   <li>将长文本规划拆成标题、总结、行程、预算、风险和假设，便于后续校验与拼装。</li>
 *   <li>通过 {@link JsonIgnoreProperties} 忽略模型偶发多输出字段，提升 LLM JSON 解析鲁棒性。</li>
 * </ul>
 * </p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlannerDraft {

    /** 规划标题，用于最终 Markdown 的一级标题 */
    private String title;

    /** 总体思路摘要，说明路线风格、节奏和核心取舍 */
    private String summary;

    /** 分天行程主体，允许使用 Markdown 以保留较好的展示结构 */
    private String itineraryMarkdown;

    /** 预算、价格不确定性、预订顺序等说明；第一阶段不做硬预算计算 */
    private String budgetNotes;

    /** 防坑、预约、交通、天气、治安等风险提醒 */
    private String riskNotes;

    /** 信息不足时模型做出的假设，Finalizer 会显式展示给用户 */
    private List<String> assumptions = new ArrayList<>();

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getItineraryMarkdown() {
        return itineraryMarkdown;
    }

    public void setItineraryMarkdown(String itineraryMarkdown) {
        this.itineraryMarkdown = itineraryMarkdown;
    }

    public String getBudgetNotes() {
        return budgetNotes;
    }

    public void setBudgetNotes(String budgetNotes) {
        this.budgetNotes = budgetNotes;
    }

    public String getRiskNotes() {
        return riskNotes;
    }

    public void setRiskNotes(String riskNotes) {
        this.riskNotes = riskNotes;
    }

    public List<String> getAssumptions() {
        return assumptions;
    }

    public void setAssumptions(List<String> assumptions) {
        this.assumptions = assumptions == null ? new ArrayList<>() : assumptions;
    }
}
