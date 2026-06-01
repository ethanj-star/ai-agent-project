package com.travel.agent.ai.graph.model;

/**
 * 局部计划修改结果。
 *
 * <p>系统架构位置：PlanLocalRevisionNode -> <b>PlanLocalRevisionResult</b> -> PlanController</p>
 *
 * <p>职责：
 * <ul>
 *   <li>承载第六阶段局部 revision 是否成功。</li>
 *   <li>成功时返回新答案和修改摘要，失败时返回错误但不破坏旧版本。</li>
 * </ul>
 * </p>
 */
public class PlanLocalRevisionResult {

    /** 局部修改是否成功。 */
    private boolean success;

    /** 修改后的完整答案。 */
    private String answer;

    /** 修改摘要，用于版本记录。 */
    private String modificationSummary;

    /** 失败时的错误摘要。 */
    private String errorMessage;

    public static PlanLocalRevisionResult success(String answer, String modificationSummary) {
        PlanLocalRevisionResult result = new PlanLocalRevisionResult();
        result.setSuccess(true);
        result.setAnswer(answer);
        result.setModificationSummary(modificationSummary);
        return result;
    }

    public static PlanLocalRevisionResult failure(String errorMessage) {
        PlanLocalRevisionResult result = new PlanLocalRevisionResult();
        result.setSuccess(false);
        result.setErrorMessage(errorMessage);
        return result;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getAnswer() {
        return answer;
    }

    public void setAnswer(String answer) {
        this.answer = answer;
    }

    public String getModificationSummary() {
        return modificationSummary;
    }

    public void setModificationSummary(String modificationSummary) {
        this.modificationSummary = modificationSummary;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
