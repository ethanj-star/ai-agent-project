package com.travel.agent.ai.dto;

import com.travel.agent.ai.graph.model.RequirementValidation;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;

/**
 * 需求表草稿响应 DTO。
 *
 * <p>系统架构位置：RequirementController -> <b>RequirementDraftResponse</b> -> 前端表单</p>
 *
 * <p>职责：
 * <ul>
 *   <li>把自然语言抽取出的结构化需求表返回给前端。</li>
 *   <li>同时携带校验结果和面向用户的提示语，让前端可以决定展示补全表单或确认按钮。</li>
 * </ul>
 * </p>
 */
public class RequirementDraftResponse {

    /** 当前需求表 ID。 */
    private String requirementId;

    /** 结构化需求表内容。 */
    private TravelRequirementSpec spec;

    /** 需求表校验结果。 */
    private RequirementValidation validation;

    /** 面向用户的简短提示，例如“还需要补充出发城市和人数”。 */
    private String assistantMessage;

    public RequirementDraftResponse() {
    }

    public RequirementDraftResponse(String requirementId,
                                    TravelRequirementSpec spec,
                                    RequirementValidation validation,
                                    String assistantMessage) {
        this.requirementId = requirementId;
        this.spec = spec;
        this.validation = validation;
        this.assistantMessage = assistantMessage;
    }

    public String getRequirementId() {
        return requirementId;
    }

    public void setRequirementId(String requirementId) {
        this.requirementId = requirementId;
    }

    public TravelRequirementSpec getSpec() {
        return spec;
    }

    public void setSpec(TravelRequirementSpec spec) {
        this.spec = spec;
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
}
