package com.travel.agent.ai.graph.model;

import com.travel.agent.ai.dto.GatekeeperResponse;

import java.util.ArrayList;
import java.util.List;

/**
 * 旅行规划工作流的全局状态对象（Graph 层 - 状态黑板）。
 *
 * <p>系统架构位置：LangGraphPlannerFacade 内部各 Node 共享的状态载体</p>
 *
 * <p>职责：
 * <ul>
 *   <li>贯穿 Init、RAG、Planner、Validator、Finalizer 全部节点。</li>
 *   <li>保存用户输入、Gatekeeper 实体、RAG 上下文、规划草案、校验问题和最终答案。</li>
 *   <li>为第二阶段迁移到 LangGraph4j StateGraph 提供稳定的状态模型。</li>
 * </ul>
 * </p>
 *
 * <p>空值策略：集合字段在 setter 中统一转为空列表，避免节点之间传递状态时产生空指针。</p>
 */
public class TravelPlanState {

    /** 用户原始输入，所有后续节点都可以回看完整需求 */
    private String userQuery;

    /** Gatekeeper 的完整路由结果，保留 intent 和 entities */
    private GatekeeperResponse route;

    /** 从 Gatekeeper entities.locations 提取出的目的地列表 */
    private List<String> destinations = new ArrayList<>();

    /** 用户提到的出行时间；缺失时由 InitStateNode 写入“未指定” */
    private String travelTime;

    /** 用户偏好、约束和其他关键词，如预算、天数、避开人多等 */
    private List<String> keywords = new ArrayList<>();

    /** 私有知识库检索出的攻略、防坑和 POI 上下文 */
    private String ragContext;

    /** Planner 节点生成的第一版结构化规划草案 */
    private PlannerDraft draft;

    /** Validator 节点发现的问题列表 */
    private List<ValidationIssue> validationIssues = new ArrayList<>();

    /** Finalizer 节点生成的最终 Markdown 答案 */
    private String finalAnswer;

    /** 当前直线流程是否成功完成 */
    private boolean success;

    /** 流程失败时的内部错误摘要 */
    private String errorMessage;

    /** 当前会话 ID，用于第二阶段保存和恢复 pending 状态。 */
    private String sessionId;

    /** 当前工作流状态，默认为正常规划中。 */
    private WorkflowStatus workflowStatus = WorkflowStatus.PLANNING;

    /** 系统正在等待用户回答的追问问题。 */
    private List<ClarificationQuestion> pendingQuestions = new ArrayList<>();

    /** 用户对澄清问题给出的补充回答，按轮次追加。 */
    private List<String> clarificationAnswers = new ArrayList<>();

    /** 当前任务已经经历的交互轮数，用于后续防止无限追问。 */
    private int turnCount;

    /** 当前轮用户输入，便于 MergeClarificationNode 和日志查看。 */
    private String lastUserMessage;

    public String getUserQuery() {
        return userQuery;
    }

    public void setUserQuery(String userQuery) {
        this.userQuery = userQuery;
    }

    public GatekeeperResponse getRoute() {
        return route;
    }

    public void setRoute(GatekeeperResponse route) {
        this.route = route;
    }

    public List<String> getDestinations() {
        return destinations;
    }

    public void setDestinations(List<String> destinations) {
        this.destinations = destinations == null ? new ArrayList<>() : destinations;
    }

    public String getTravelTime() {
        return travelTime;
    }

    public void setTravelTime(String travelTime) {
        this.travelTime = travelTime;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords == null ? new ArrayList<>() : keywords;
    }

    public String getRagContext() {
        return ragContext;
    }

    public void setRagContext(String ragContext) {
        this.ragContext = ragContext;
    }

    public PlannerDraft getDraft() {
        return draft;
    }

    public void setDraft(PlannerDraft draft) {
        this.draft = draft;
    }

    public List<ValidationIssue> getValidationIssues() {
        return validationIssues;
    }

    public void setValidationIssues(List<ValidationIssue> validationIssues) {
        this.validationIssues = validationIssues == null ? new ArrayList<>() : validationIssues;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public void setFinalAnswer(String finalAnswer) {
        this.finalAnswer = finalAnswer;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public WorkflowStatus getWorkflowStatus() {
        return workflowStatus;
    }

    public void setWorkflowStatus(WorkflowStatus workflowStatus) {
        this.workflowStatus = workflowStatus == null ? WorkflowStatus.PLANNING : workflowStatus;
    }

    public List<ClarificationQuestion> getPendingQuestions() {
        return pendingQuestions;
    }

    public void setPendingQuestions(List<ClarificationQuestion> pendingQuestions) {
        this.pendingQuestions = pendingQuestions == null ? new ArrayList<>() : pendingQuestions;
    }

    public List<String> getClarificationAnswers() {
        return clarificationAnswers;
    }

    public void setClarificationAnswers(List<String> clarificationAnswers) {
        this.clarificationAnswers = clarificationAnswers == null ? new ArrayList<>() : clarificationAnswers;
    }

    public int getTurnCount() {
        return turnCount;
    }

    public void setTurnCount(int turnCount) {
        this.turnCount = turnCount;
    }

    public String getLastUserMessage() {
        return lastUserMessage;
    }

    public void setLastUserMessage(String lastUserMessage) {
        this.lastUserMessage = lastUserMessage;
    }
}
