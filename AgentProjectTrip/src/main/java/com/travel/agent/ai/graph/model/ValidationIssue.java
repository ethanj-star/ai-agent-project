package com.travel.agent.ai.graph.model;

/**
 * Validator 节点发现的结构化问题（Graph 层 - 校验协议）。
 *
 * <p>系统架构位置：ValidateDraftNode → <b>ValidationIssue</b> → FinalizeAnswerNode</p>
 *
 * <p>职责：
 * <ul>
 *   <li>表达规划草案中的缺失信息、弱约束、风险或质量问题。</li>
 *   <li>用 severity 区分问题等级，便于后续阶段决定是否自动修正或追问用户。</li>
 *   <li>用 code 提供机器可读的问题类型，避免只依赖自然语言 message。</li>
 * </ul>
 * </p>
 */
public class ValidationIssue {

    /** 高严重度：通常意味着需要补充信息或后续自动修正 */
    public static final String HIGH = "HIGH";

    /** 中严重度：可以输出方案，但应提醒用户后续继续确认 */
    public static final String MEDIUM = "MEDIUM";

    /** 低严重度：主要作为风险提示或上下文不足提示 */
    public static final String LOW = "LOW";

    /** 问题等级：HIGH / MEDIUM / LOW */
    private String severity;

    /** 机器可读的问题代码，如 MISSING_DATE、EMPTY_DRAFT */
    private String code;

    /** 面向用户或日志的自然语言说明 */
    private String message;

    /**
     * Jackson / 测试场景需要的无参构造器。
     */
    public ValidationIssue() {
    }

    /**
     * 构造一个校验问题。
     *
     * @param severity 问题等级
     * @param code     机器可读的问题代码
     * @param message  自然语言说明
     */
    public ValidationIssue(String severity, String code, String message) {
        this.severity = severity;
        this.code = code;
        this.message = message;
    }

    /**
     * 创建高严重度问题。
     */
    public static ValidationIssue high(String code, String message) {
        return new ValidationIssue(HIGH, code, message);
    }

    /**
     * 创建中严重度问题。
     */
    public static ValidationIssue medium(String code, String message) {
        return new ValidationIssue(MEDIUM, code, message);
    }

    /**
     * 创建低严重度问题。
     */
    public static ValidationIssue low(String code, String message) {
        return new ValidationIssue(LOW, code, message);
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public String getCode() {
        return code;
    }

    public void setCode(String code) {
        this.code = code;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
