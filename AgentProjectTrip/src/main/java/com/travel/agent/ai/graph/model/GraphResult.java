package com.travel.agent.ai.graph.model;

import java.util.ArrayList;
import java.util.List;

/**
 * LangGraph 规划黑箱的出口结果对象（Graph 层 - 出参协议）。
 *
 * <p>系统架构位置：LangGraphPlannerFacade → <b>GraphResult</b> → MastermindAgent</p>
 *
 * <p>职责：
 * <ul>
 *   <li>向上层返回最终可展示的 Markdown 文本。</li>
 *   <li>携带 Validator 发现的问题，便于后续阶段做前端提示或自动修正循环。</li>
 *   <li>在 Graph 内部节点失败时提供统一失败结果，避免异常直接穿透到 Web 层。</li>
 * </ul>
 * </p>
 */
public class GraphResult {

    /** Graph 流程是否成功完成；失败时上层应优先使用 answer 中的降级回复 */
    private boolean success;

    /** 最终返回给用户的自然语言 / Markdown 答案 */
    private String answer;

    /** Validator 节点产出的结构化问题列表；为空表示当前草案未发现显式问题 */
    private List<ValidationIssue> validationIssues = new ArrayList<>();

    /** Graph 失败时的内部错误摘要，仅用于日志或调试，不直接作为主要用户答案 */
    private String errorMessage;

    /**
     * 构造成功结果。
     *
     * @param answer           最终 Markdown 答案
     * @param validationIssues Validator 发现的问题列表，可为空
     * @return success=true 的 GraphResult
     */
    public static GraphResult success(String answer, List<ValidationIssue> validationIssues) {
        GraphResult result = new GraphResult();
        result.setSuccess(true);
        result.setAnswer(answer);
        result.setValidationIssues(validationIssues);
        return result;
    }

    /**
     * 构造失败结果。
     *
     * <p>即使 Graph 失败，也保留一个可给用户展示的降级答案，避免 Controller 返回空响应或 500。</p>
     *
     * @param answer       面向用户的降级回复
     * @param errorMessage 内部错误摘要
     * @return success=false 的 GraphResult
     */
    public static GraphResult failure(String answer, String errorMessage) {
        GraphResult result = new GraphResult();
        result.setSuccess(false);
        result.setAnswer(answer);
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

    public List<ValidationIssue> getValidationIssues() {
        return validationIssues;
    }

    public void setValidationIssues(List<ValidationIssue> validationIssues) {
        this.validationIssues = validationIssues == null ? new ArrayList<>() : validationIssues;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
