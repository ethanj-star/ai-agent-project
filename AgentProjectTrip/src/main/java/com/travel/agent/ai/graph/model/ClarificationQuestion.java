package com.travel.agent.ai.graph.model;

/**
 * 系统向用户发起的结构化澄清问题。
 *
 * <p>系统架构位置：ValidateDraftNode -> ClarifyQuestionNode -> <b>ClarificationQuestion</b> -> TravelPlanState</p>
 *
 * <p>职责：
 * <ul>
 *   <li>保存一次追问里的单个问题，避免只用自然语言文本导致后续无法判断用户回答了什么。</li>
 *   <li>用 field 标记问题对应的业务字段，例如目的地、预算、天数或偏好。</li>
 *   <li>为第三阶段前端结构化展示问题、按钮选项或表单控件预留协议。</li>
 * </ul>
 * </p>
 */
public class ClarificationQuestion {

    /** 问题唯一标识，例如 destination_scope、budget_scope。 */
    private String id;

    /** 问题对应的业务字段，例如 destinations、budget、travelDays。 */
    private String field;

    /** 面向用户展示的自然语言追问。 */
    private String question;

    /** 是否必须回答后才适合继续规划。 */
    private boolean required;

    /**
     * Jackson / 测试场景需要的无参构造器。
     */
    public ClarificationQuestion() {
    }

    /**
     * 构造一个结构化澄清问题。
     *
     * @param id       问题唯一标识
     * @param field    关联业务字段
     * @param question 用户可读的追问文本
     * @param required 是否必须回答
     */
    public ClarificationQuestion(String id, String field, String question, boolean required) {
        this.id = id;
        this.field = field;
        this.question = question;
        this.required = required;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }

    public boolean isRequired() {
        return required;
    }

    public void setRequired(boolean required) {
        this.required = required;
    }
}
