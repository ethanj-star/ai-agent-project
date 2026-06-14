package com.travel.agent.ai.graph.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 风险推理节点的结构化输出。
 *
 * <p>系统架构位置：TripRiskReasoningNode -> <b>RiskAssessment</b> -> LangGraphPlannerFacade</p>
 *
 * <p>职责：
 * <ul>
 *   <li>汇总草案审查后的风险问题。</li>
 *   <li>告诉 Facade 当前任务应该自动修正、追问用户，还是直接进入最终输出。</li>
 *   <li>为 PlanRevisionNode 提供可执行的 revisionInstruction。</li>
 * </ul>
 * </p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RiskAssessment {

    /** 是否需要 PlanRevisionNode 自动修正。 */
    private boolean needsRevision;

    /** 是否需要 ClarifyQuestionNode 追问用户。 */
    private boolean needsClarification;

    /** 风险问题列表。 */
    private List<RiskIssue> issues = new ArrayList<>();

    /** 汇总后的修正指令，直接供 PlanRevisionNode 注入 prompt。 */
    private String revisionInstruction;

    /**
     * Jackson / 测试场景需要的无参构造器。
     */
    public RiskAssessment() {
    }

    public RiskAssessment(boolean needsRevision,
                          boolean needsClarification,
                          List<RiskIssue> issues,
                          String revisionInstruction) {
        this.needsRevision = needsRevision;
        this.needsClarification = needsClarification;
        setIssues(issues);
        this.revisionInstruction = revisionInstruction;
    }

    /**
     * 创建一个无风险审查结果。
     */
    public static RiskAssessment clear() {
        // clear 表示审查通过：不需要追问、不需要修正，也没有风险条目。
        return new RiskAssessment(false, false, new ArrayList<>(), null);
    }

    public boolean isNeedsRevision() {
        return needsRevision;
    }

    public void setNeedsRevision(boolean needsRevision) {
        this.needsRevision = needsRevision;
    }

    public boolean isNeedsClarification() {
        return needsClarification;
    }

    public void setNeedsClarification(boolean needsClarification) {
        this.needsClarification = needsClarification;
    }

    public List<RiskIssue> getIssues() {
        return issues;
    }

    public void setIssues(List<RiskIssue> issues) {
        // 风险列表保持非 null，最终答案拼风险提醒时可以直接遍历。
        this.issues = issues == null ? new ArrayList<>() : issues;
    }

    public String getRevisionInstruction() {
        return revisionInstruction;
    }

    public void setRevisionInstruction(String revisionInstruction) {
        this.revisionInstruction = revisionInstruction;
    }
}
