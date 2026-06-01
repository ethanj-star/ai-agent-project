package com.travel.agent.ai.dto;

import com.travel.agent.ai.graph.model.GraphResult;
import com.travel.agent.ai.graph.model.RequirementStatus;

/**
 * 需求表生成完整规划响应 DTO。
 *
 * <p>系统架构位置：GenerationGate -> LangGraphPlannerFacade -> <b>RequirementGenerateResponse</b> -> 前端</p>
 *
 * <p>职责：
 * <ul>
 *   <li>返回完整规划结果和需求表状态。</li>
 *   <li>携带剩余额度，方便前端展示“本次已消耗一次生成额度”。</li>
 * </ul>
 * </p>
 */
public class RequirementGenerateResponse {

    /** 当前需求表 ID。 */
    private String requirementId;

    /** 生成后的需求表状态。 */
    private RequirementStatus status;

    /** 当前 session 剩余模拟生成额度。 */
    private int remainingCredits;

    /** 现有 Graph 黑箱返回的完整规划结果。 */
    private GraphResult graphResult;

    public RequirementGenerateResponse() {
    }

    public RequirementGenerateResponse(String requirementId,
                                       RequirementStatus status,
                                       int remainingCredits,
                                       GraphResult graphResult) {
        this.requirementId = requirementId;
        this.status = status;
        this.remainingCredits = remainingCredits;
        this.graphResult = graphResult;
    }

    public String getRequirementId() {
        return requirementId;
    }

    public void setRequirementId(String requirementId) {
        this.requirementId = requirementId;
    }

    public RequirementStatus getStatus() {
        return status;
    }

    public void setStatus(RequirementStatus status) {
        this.status = status;
    }

    public int getRemainingCredits() {
        return remainingCredits;
    }

    public void setRemainingCredits(int remainingCredits) {
        this.remainingCredits = remainingCredits;
    }

    public GraphResult getGraphResult() {
        return graphResult;
    }

    public void setGraphResult(GraphResult graphResult) {
        this.graphResult = graphResult;
    }
}
