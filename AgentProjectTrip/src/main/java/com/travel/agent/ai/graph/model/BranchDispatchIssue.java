package com.travel.agent.ai.graph.model;

/**
 * 分支派发 Guard 记录的接受、拒绝、裁剪或降级原因。
 *
 * <p>系统架构位置：BranchDispatchGuardNode -> <b>BranchDispatchIssue</b> -> TravelPlanState</p>
 *
 * <p>职责：
 * <ul>
 *   <li>解释模型建议为什么被拒绝、裁剪或触发 fallback。</li>
 *   <li>为后续 Trace 日志和前端调试面板保留机器可读线索。</li>
 *   <li>不参与工具执行，只描述调度决策边界。</li>
 * </ul>
 * </p>
 */
public class BranchDispatchIssue {

    /** 模型建议或 Guard 处理的任务类型文本。 */
    private String type;

    /** 处理动作，例如 ACCEPTED / REJECTED / TRIMMED / FALLBACK。 */
    private String action;

    /** 具体原因，面向开发调试和后续 Trace。 */
    private String reason;

    public BranchDispatchIssue() {
    }

    public BranchDispatchIssue(String type, String action, String reason) {
        this.type = cleanText(type);
        this.action = cleanText(action);
        this.reason = cleanText(reason);
    }

    public static BranchDispatchIssue accepted(String type, String reason) {
        return new BranchDispatchIssue(type, "ACCEPTED", reason);
    }

    public static BranchDispatchIssue rejected(String type, String reason) {
        return new BranchDispatchIssue(type, "REJECTED", reason);
    }

    public static BranchDispatchIssue trimmed(String type, String reason) {
        return new BranchDispatchIssue(type, "TRIMMED", reason);
    }

    public static BranchDispatchIssue fallback(String reason) {
        return new BranchDispatchIssue("RULE_BASED", "FALLBACK", reason);
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = cleanText(type);
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = cleanText(action);
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = cleanText(reason);
    }

    private static String cleanText(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
