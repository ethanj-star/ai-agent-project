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
 *   <li>贯穿 Init、RAG、Branch、Planner、Validator、RiskReasoning、Revision、Finalizer 全部节点。</li>
 *   <li>保存用户输入、Gatekeeper 实体、结构化需求表、行程时长、RAG 上下文、分支派发决策、规划草案、风险审查、校验问题和最终答案。</li>
 *   <li>为第二阶段迁移到 LangGraph4j StateGraph 提供稳定的状态模型。</li>
 * </ul>
 * </p>
 *
 * <p>空值策略：集合字段在 setter 中统一转为空列表，避免节点之间传递状态时产生空指针。</p>
 */
public class TravelPlanState {

    /** 用户原始输入，所有后续节点都可以回看完整需求 */
    private String userQuery;

    /** 第五阶段确认后的结构化旅行需求表；存在时它是 Planner 和 RiskReasoning 的优先事实来源。 */
    private TravelRequirementSpec requirementSpec;

    /** Gatekeeper 的完整路由结果，保留 intent 和 entities */
    private GatekeeperResponse route;

    /** 从 Gatekeeper entities.locations 提取出的目的地列表 */
    private List<String> destinations = new ArrayList<>();

    /** 用户提到的出行时间；缺失时由 InitStateNode 写入“未指定” */
    private String travelTime;

    /** 归一化后的行程天数，例如“10天”会写入 10。 */
    private Integer durationDays;

    /** 用户原始时长表达，例如“10天”“一周左右”“5晚6天”。 */
    private String durationText;

    /** 用户偏好、约束和其他关键词，如预算、避开人多等；行程天数会单独写入 duration 字段。 */
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

    /** 第三阶段由 BranchDispatchNode 生成的分支任务列表。 */
    private List<BranchTask> branchTasks = new ArrayList<>();

    /** 第十三阶段模型对分支任务的原始建议；模型失败时会记录 fallback 原因。 */
    private BranchDispatchDecision branchDispatchDecision;

    /** 第十三阶段 Java Guard 对模型建议的接受、拒绝、裁剪或回退记录。 */
    private List<BranchDispatchIssue> branchDispatchIssues = new ArrayList<>();

    /** 第三阶段由 BranchExecuteNode 写入的分支执行结果。 */
    private List<BranchResult> branchResults = new ArrayList<>();

    /** 第四阶段风险推理节点输出的结构化审查结果。 */
    private RiskAssessment riskAssessment;

    /** 第七阶段注入的用户记忆摘要；只作为 Planner 参考，不覆盖本次明确需求。 */
    private String userMemoryContext;

    /** 当前已经执行过的自动修正次数。 */
    private int revisionCount;

    /** 单次规划最多允许自动修正次数，避免 revision 循环失控。 */
    private int maxRevisionCount = 1;

    public String getUserQuery() {
        return userQuery;
    }

    public void setUserQuery(String userQuery) {
        this.userQuery = userQuery;
    }

    public TravelRequirementSpec getRequirementSpec() {
        return requirementSpec;
    }

    public void setRequirementSpec(TravelRequirementSpec requirementSpec) {
        this.requirementSpec = requirementSpec;
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
        // 状态对象在多个节点间传递，集合统一保持非 null，节点代码就不用反复判空。
        this.destinations = destinations == null ? new ArrayList<>() : destinations;
    }

    public String getTravelTime() {
        return travelTime;
    }

    public void setTravelTime(String travelTime) {
        this.travelTime = travelTime;
    }

    public Integer getDurationDays() {
        return durationDays;
    }

    public void setDurationDays(Integer durationDays) {
        this.durationDays = durationDays;
    }

    public String getDurationText() {
        return durationText;
    }

    public void setDurationText(String durationText) {
        this.durationText = durationText;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public void setKeywords(List<String> keywords) {
        // 关键词为空表示“没有额外偏好”，不应该让后续 prompt 拼接处出现空指针。
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
        // Validator 没发现问题时写空列表，Finalizer 和 API 响应可直接读取。
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
        // 空状态默认回到正常规划中，避免反序列化旧状态时中断流程。
        this.workflowStatus = workflowStatus == null ? WorkflowStatus.PLANNING : workflowStatus;
    }

    public List<ClarificationQuestion> getPendingQuestions() {
        return pendingQuestions;
    }

    public void setPendingQuestions(List<ClarificationQuestion> pendingQuestions) {
        // 没有待追问问题时使用空列表，表示流程可以继续或已经完成。
        this.pendingQuestions = pendingQuestions == null ? new ArrayList<>() : pendingQuestions;
    }

    public List<String> getClarificationAnswers() {
        return clarificationAnswers;
    }

    public void setClarificationAnswers(List<String> clarificationAnswers) {
        // 用户尚未回答追问时为空列表，方便 MergeClarificationNode 追加当前回答。
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

    public List<BranchTask> getBranchTasks() {
        return branchTasks;
    }

    public void setBranchTasks(List<BranchTask> branchTasks) {
        // BranchDispatchNode 不需要分支时写空列表，BranchExecuteNode 会据此直接跳过。
        this.branchTasks = branchTasks == null ? new ArrayList<>() : branchTasks;
    }

    public BranchDispatchDecision getBranchDispatchDecision() {
        return branchDispatchDecision;
    }

    public void setBranchDispatchDecision(BranchDispatchDecision branchDispatchDecision) {
        this.branchDispatchDecision = branchDispatchDecision;
    }

    public List<BranchDispatchIssue> getBranchDispatchIssues() {
        return branchDispatchIssues;
    }

    public void setBranchDispatchIssues(List<BranchDispatchIssue> branchDispatchIssues) {
        // Guard 没有拒绝任何任务时写空列表，后续 Trace 面板可以直接遍历。
        this.branchDispatchIssues = branchDispatchIssues == null ? new ArrayList<>() : branchDispatchIssues;
    }

    public List<BranchResult> getBranchResults() {
        return branchResults;
    }

    public void setBranchResults(List<BranchResult> branchResults) {
        // 即使没有实时工具结果，也保持空列表，Planner 可按“无外部补充”处理。
        this.branchResults = branchResults == null ? new ArrayList<>() : branchResults;
    }

    public RiskAssessment getRiskAssessment() {
        return riskAssessment;
    }

    public void setRiskAssessment(RiskAssessment riskAssessment) {
        this.riskAssessment = riskAssessment;
    }

    public String getUserMemoryContext() {
        return userMemoryContext;
    }

    public void setUserMemoryContext(String userMemoryContext) {
        this.userMemoryContext = userMemoryContext;
    }

    public int getRevisionCount() {
        return revisionCount;
    }

    public void setRevisionCount(int revisionCount) {
        // 修正次数不能小于 0，防止外部恢复状态时传入异常值导致循环判断失效。
        this.revisionCount = Math.max(0, revisionCount);
    }

    public int getMaxRevisionCount() {
        return maxRevisionCount;
    }

    public void setMaxRevisionCount(int maxRevisionCount) {
        // 最大修正次数允许为 0，表示完全关闭自动 revision，但不能是负数。
        this.maxRevisionCount = Math.max(0, maxRevisionCount);
    }
}
