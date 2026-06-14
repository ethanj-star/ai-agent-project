package com.travel.agent.ai.graph.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * 风险审查发现的单个问题。
 *
 * <p>系统架构位置：TripRiskReasoningNode -> <b>RiskIssue</b> -> PlanRevisionNode / FinalizeAnswerNode</p>
 *
 * <p>职责：
 * <ul>
 *   <li>描述草案、用户约束、RAG 或分支工具结果之间的冲突。</li>
 *   <li>携带证据和修正建议，供 PlanRevisionNode 生成重写提示词。</li>
 *   <li>显式区分可自动修正问题和必须追问用户的问题。</li>
 * </ul>
 * </p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class RiskIssue {

    /** 风险问题类型。 */
    private RiskIssueType type;

    /** 风险严重程度。 */
    private RiskSeverity severity;

    /** 机器可读问题代码，便于测试和后续统计。 */
    private String code;

    /** 问题发生的行程位置，例如“第3天”；可为空。 */
    private String day;

    /** 面向用户或日志的问题摘要。 */
    private String message;

    /** 触发问题的证据，例如用户偏好、草案片段或工具结果。 */
    private String evidence;

    /** 给自动修正节点的建议动作。 */
    private String suggestedAction;

    /** 是否可以由系统自动修正。 */
    private boolean autoRevisable;

    /** 是否必须追问用户才能继续。 */
    private boolean requiresClarification;

    /**
     * Jackson / 测试场景需要的无参构造器。
     */
    public RiskIssue() {
    }

    public RiskIssue(RiskIssueType type,
                     RiskSeverity severity,
                     String code,
                     String day,
                     String message,
                     String evidence,
                     String suggestedAction,
                     boolean autoRevisable,
                     boolean requiresClarification) {
        this.type = type;
        this.severity = severity;
        this.code = code;
        this.day = day;
        this.message = message;
        this.evidence = evidence;
        this.suggestedAction = suggestedAction;
        this.autoRevisable = autoRevisable;
        this.requiresClarification = requiresClarification;
    }

    /**
     * 创建一个可自动修正的问题。
     */
    public static RiskIssue autoRevisable(RiskIssueType type,
                                          RiskSeverity severity,
                                          String code,
                                          String message,
                                          String evidence,
                                          String suggestedAction) {
        // 可自动修正的问题会触发 PlanRevisionNode，不需要先打断用户对话。
        return new RiskIssue(type, severity, code, null, message, evidence, suggestedAction, true, false);
    }

    /**
     * 创建一个只需要提示用户的非阻塞问题。
     */
    public static RiskIssue warning(RiskIssueType type,
                                    RiskSeverity severity,
                                    String code,
                                    String message,
                                    String evidence) {
        // warning 只进入最终提醒，不触发自动重写，也不阻塞输出。
        return new RiskIssue(type, severity, code, null, message, evidence, null, false, false);
    }

    public RiskIssueType getType() {
        return type;
    }

    public void setType(RiskIssueType type) {
        this.type = type;
    }

    public RiskSeverity getSeverity() {
        return severity;
    }

    public void setSeverity(RiskSeverity severity) {
        this.severity = severity;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getDay() {
        return day;
    }

    public void setDay(String day) {
        this.day = day;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getEvidence() {
        return evidence;
    }

    public void setEvidence(String evidence) {
        this.evidence = evidence;
    }

    public String getSuggestedAction() {
        return suggestedAction;
    }

    public void setSuggestedAction(String suggestedAction) {
        this.suggestedAction = suggestedAction;
    }

    public boolean isAutoRevisable() {
        return autoRevisable;
    }

    public void setAutoRevisable(boolean autoRevisable) {
        this.autoRevisable = autoRevisable;
    }

    public boolean isRequiresClarification() {
        return requiresClarification;
    }

    public void setRequiresClarification(boolean requiresClarification) {
        this.requiresClarification = requiresClarification;
    }
}
