package com.travel.agent.ai.dto;

import com.travel.agent.ai.graph.model.PlanModificationIntent;
import com.travel.agent.ai.graph.model.RequirementValidation;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;

/**
 * 已有计划自然语言修改响应 DTO。
 *
 * <p>系统架构位置：PlanController -> <b>PlanModificationResponse</b> -> 前端</p>
 *
 * <p>职责：
 * <ul>
 *   <li>统一承载局部修改成功、核心需求变更待确认、需要追问和普通评论四类结果。</li>
 *   <li>局部修改成功时返回新版本号和新答案。</li>
 *   <li>核心需求变更时返回更新后的需求表和校验结果。</li>
 * </ul>
 * </p>
 */
public class PlanModificationResponse {

    /** 计划 ID。 */
    private String planId;

    /** 响应状态，例如 UPDATED / REQUIREMENT_NEEDS_CONFIRMATION / NEEDS_CLARIFICATION。 */
    private String status;

    /** 新版本号；不新增版本时为空。 */
    private Integer version;

    /** 修改意图类型。 */
    private PlanModificationIntent modificationIntent;

    /** 修改后的完整答案；局部修改成功时返回。 */
    private String answer;

    /** 更新后的需求表；核心需求变更时返回。 */
    private TravelRequirementSpec requirementSpec;

    /** 需求表校验结果；核心需求变更时返回。 */
    private RequirementValidation validation;

    /** 面向用户的提示语。 */
    private String assistantMessage;

    /** 需要追问时的问题。 */
    private String question;

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getVersion() {
        return version;
    }

    public void setVersion(Integer version) {
        this.version = version;
    }

    public PlanModificationIntent getModificationIntent() {
        return modificationIntent;
    }

    public void setModificationIntent(PlanModificationIntent modificationIntent) {
        this.modificationIntent = modificationIntent;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public TravelRequirementSpec getRequirementSpec() {
        return requirementSpec;
    }

    public void setRequirementSpec(TravelRequirementSpec requirementSpec) {
        this.requirementSpec = requirementSpec;
    }

    public RequirementValidation getValidation() {
        return validation;
    }

    public void setValidation(RequirementValidation validation) {
        this.validation = validation;
    }

    public String getAssistantMessage() {
        return assistantMessage;
    }

    public void setAssistantMessage(String assistantMessage) {
        this.assistantMessage = assistantMessage;
    }

    public String getQuestion() {
        return question;
    }

    public void setQuestion(String question) {
        this.question = question;
    }
}
