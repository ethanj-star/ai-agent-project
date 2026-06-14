package com.travel.agent.ai.generation;

import com.travel.agent.ai.dto.RequirementGenerateResponse;
import com.travel.agent.ai.graph.model.GraphResult;
import com.travel.agent.ai.graph.model.RequirementStatus;

/**
 * 完整旅行规划生成结果。
 *
 * <p>系统架构位置：PlanGenerationService -> <b>PlanGenerationOutcome</b> -> RequirementController / AsyncPlanGenerationService</p>
 *
 * <p>职责：
 * <ul>
 *   <li>承载一次完整生成的业务结果，包括 GraphResult、planId、需求状态和剩余额度。</li>
 *   <li>把同步 HTTP 响应和异步 GenerationJob 结果统一到同一份业务输出。</li>
 *   <li>记录扣费是否最终保留、是否发生退款，方便异步任务审计。</li>
 * </ul>
 * </p>
 */
public class PlanGenerationOutcome {

    /** 当前需求表 ID。 */
    private String requirementId;

    /** 生成成功后保存的计划 ID。 */
    private String planId;

    /** 生成结束后的需求表状态。 */
    private RequirementStatus status;

    /** 当前 session 剩余生成额度。 */
    private int remainingCredits;

    /** Graph 黑箱输出结果；失败时也携带降级说明。 */
    private GraphResult graphResult;

    /** 同步接口建议返回的 HTTP 状态码。 */
    private int httpStatusCode = 200;

    /** 最终是否保留了本次生成扣费。 */
    private boolean creditCharged;

    /** 本次生成是否因失败执行过退款。 */
    private boolean refunded;

    public String getRequirementId() {
        return requirementId;
    }

    public void setRequirementId(String requirementId) {
        this.requirementId = requirementId;
    }

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
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

    public int getHttpStatusCode() {
        return httpStatusCode;
    }

    public void setHttpStatusCode(int httpStatusCode) {
        this.httpStatusCode = httpStatusCode;
    }

    public boolean isCreditCharged() {
        return creditCharged;
    }

    public void setCreditCharged(boolean creditCharged) {
        this.creditCharged = creditCharged;
    }

    public boolean isRefunded() {
        return refunded;
    }

    public void setRefunded(boolean refunded) {
        this.refunded = refunded;
    }

    /**
     * 判断本次业务生成是否真正成功。
     *
     * @return Graph 成功且 planId 不为空时返回 true
     */
    public boolean isSucceeded() {
        return graphResult != null && graphResult.isSuccess() && planId != null && !planId.isBlank();
    }

    /**
     * 转换为旧同步接口的响应 DTO。
     *
     * @return RequirementGenerateResponse
     */
    public RequirementGenerateResponse toResponse() {
        RequirementGenerateResponse response = new RequirementGenerateResponse(
                requirementId,
                status,
                remainingCredits,
                graphResult);
        response.setPlanId(planId);
        return response;
    }
}
