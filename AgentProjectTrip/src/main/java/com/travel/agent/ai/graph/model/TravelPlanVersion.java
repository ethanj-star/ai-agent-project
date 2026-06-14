package com.travel.agent.ai.graph.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * 旅行计划版本记录。
 *
 * <p>系统架构位置：LangGraphPlannerFacade / PlanLocalRevisionNode -> <b>TravelPlanVersion</b> -> TravelPlanStore</p>
 *
 * <p>职责：
 * <ul>
 *   <li>保存某一次完整生成或修改后的计划文本和关联审查信息。</li>
 *   <li>为第六阶段版本化、回滚和后续差异对比提供最小数据单元。</li>
 *   <li>第一版允许 draft 和 riskAssessment 为空，先保证 finalAnswer 可以追踪。</li>
 * </ul>
 * </p>
 */
public class TravelPlanVersion {

    /** 从 1 开始递增的版本号。 */
    private int version;

    /** 该版本对应的 Planner 草案；第一版 GraphResult 暂未携带时可为空。 */
    private PlannerDraft draft;

    /** 该版本最终展示给用户的 Markdown 答案。 */
    private String finalAnswer;

    /** 该版本输出前的风险审查结果；可为空。 */
    private RiskAssessment riskAssessment;

    /** 该版本的校验问题列表。 */
    private List<ValidationIssue> validationIssues = new ArrayList<>();

    /** 本版本修改摘要，例如“放松第3天节奏”。 */
    private String modificationSummary;

    /** 触发该版本的用户自然语言指令。 */
    private String userInstruction;

    /** 版本创建时间。 */
    private Instant createdAt = Instant.now();

    public int getVersion() {
        return version;
    }

    public void setVersion(int version) {
        // 单个版本号也从 1 开始，保持和 TravelPlanRecord 的约束一致。
        this.version = Math.max(1, version);
    }

    public PlannerDraft getDraft() {
        return draft;
    }

    public void setDraft(PlannerDraft draft) {
        this.draft = draft;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    public RiskAssessment getRiskAssessment() {
        return riskAssessment;
    }

    public void setRiskAssessment(RiskAssessment riskAssessment) {
        this.riskAssessment = riskAssessment;
    }

    public List<ValidationIssue> getValidationIssues() {
        return validationIssues;
    }

    public void setValidationIssues(List<ValidationIssue> validationIssues) {
        // 没有校验问题时保存空列表，历史版本详情接口可以稳定返回数组。
        this.validationIssues = validationIssues == null ? new ArrayList<>() : validationIssues;
    }

    public String getModificationSummary() {
        return modificationSummary;
    }

    public void setModificationSummary(String modificationSummary) {
        this.modificationSummary = modificationSummary;
    }

    public String getUserInstruction() {
        return userInstruction;
    }

    public void setUserInstruction(String userInstruction) {
        this.userInstruction = userInstruction;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
    }
}
