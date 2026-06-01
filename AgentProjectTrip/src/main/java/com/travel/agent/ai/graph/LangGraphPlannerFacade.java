package com.travel.agent.ai.graph;

import com.travel.agent.ai.graph.model.GraphInputRequest;
import com.travel.agent.ai.graph.model.GraphResult;
import com.travel.agent.ai.graph.model.RiskIssue;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.ValidationIssue;
import com.travel.agent.ai.graph.model.WorkflowStatus;
import com.travel.agent.ai.graph.node.BranchDispatchNode;
import com.travel.agent.ai.graph.node.BranchExecuteNode;
import com.travel.agent.ai.graph.node.ClarifyQuestionNode;
import com.travel.agent.ai.graph.node.FinalizeAnswerNode;
import com.travel.agent.ai.graph.node.InitStateNode;
import com.travel.agent.ai.graph.node.MergeClarificationNode;
import com.travel.agent.ai.graph.node.PlanDraftNode;
import com.travel.agent.ai.graph.node.PlanRevisionNode;
import com.travel.agent.ai.graph.node.PreClarifyCheckNode;
import com.travel.agent.ai.graph.node.RetrieveKnowledgeNode;
import com.travel.agent.ai.graph.node.TripRiskReasoningNode;
import com.travel.agent.ai.graph.node.ValidateDraftNode;
import com.travel.agent.ai.graph.store.ConversationStateStore;
import com.travel.agent.ai.graph.store.InMemoryConversationStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;

/**
 * 旅行规划 Graph 工作流门面。
 *
 * <p>系统架构位置：MastermindAgent -> <b>LangGraphPlannerFacade</b> -> Graph Nodes</p>
 *
 * <p>职责：
 * <ul>
 *   <li>作为 Spring AI 外围调度层和后续 LangGraph4j 状态图之间的稳定边界。</li>
 *   <li>串联 Init、RAG、Branch、Planner、Validator、Clarify、Finalizer 等节点。</li>
 *   <li>在第二阶段支持“信息不足 -> 追问用户 -> 保存 pending 状态 -> 用户补充后续跑”的闭环。</li>
 *   <li>在第三阶段支持“核心 Graph 派发分支任务 -> 分支 Agent 调工具 -> 结果回填 Planner”的直线闭环。</li>
 *   <li>在第四阶段支持“Planner -> RiskReasoning -> Revision -> Finalizer”的自动修正闭环。</li>
 *   <li>统一捕获节点异常，返回 {@link GraphResult} 降级结果，避免 Graph 内部异常穿透到 Web 层。</li>
 * </ul>
 * </p>
 */
@Service
public class LangGraphPlannerFacade {

    private static final Logger log = LoggerFactory.getLogger(LangGraphPlannerFacade.class);

    /** 用户未传 sessionId 时使用的兜底会话 ID。 */
    private static final String DEFAULT_SESSION_ID = "default-session";

    /** Graph 流程整体失败时返回给用户的兜底答案。 */
    private static final String FAILURE_ANSWER =
            "抱歉，规划流程暂时遇到问题。请稍后重试，或先补充目的地、出行时间、预算和偏好。";

    /** 初始化 TravelPlanState 的入口节点。 */
    private final InitStateNode initStateNode;

    /** 从私有知识库检索 RAG 上下文的节点。 */
    private final RetrieveKnowledgeNode retrieveKnowledgeNode;

    /** 根据状态生成天气、景点、航班等分支任务的节点。 */
    private final BranchDispatchNode branchDispatchNode;

    /** 调用分支 Agent 执行任务并回填分支结果的节点。 */
    private final BranchExecuteNode branchExecuteNode;

    /** 调用核心模型生成 PlannerDraft 的节点。 */
    private final PlanDraftNode planDraftNode;

    /** 基于规则校验草案质量和信息缺口的节点。 */
    private final ValidateDraftNode validateDraftNode;

    /** 输出前综合审查草案风险和用户约束冲突的节点。 */
    private final TripRiskReasoningNode tripRiskReasoningNode;

    /** 根据风险审查结果自动重写草案的节点。 */
    private final PlanRevisionNode planRevisionNode;

    /** 将阻塞性缺口转换成用户追问的节点。 */
    private final ClarifyQuestionNode clarifyQuestionNode;

    /** 将用户补充信息合并回旧任务状态的节点。 */
    private final MergeClarificationNode mergeClarificationNode;

    /** 在 RAG 和核心模型之前执行低成本澄清判断的节点。 */
    private final PreClarifyCheckNode preClarifyCheckNode;

    /** 将草案和校验结果拼装成最终 Markdown 的节点。 */
    private final FinalizeAnswerNode finalizeAnswerNode;

    /** 第二阶段保存 pending 状态的会话仓库。 */
    private final ConversationStateStore conversationStateStore;

    /**
     * 构造器注入当前规划工作流需要的全部节点。
     *
     * <p>后续迁移到 LangGraph4j {@code StateGraph} 时，对外仍保持 {@link #plan(GraphInputRequest)} 方法不变。</p>
     */
    @Autowired
    public LangGraphPlannerFacade(InitStateNode initStateNode,
                                  RetrieveKnowledgeNode retrieveKnowledgeNode,
                                  BranchDispatchNode branchDispatchNode,
                                  BranchExecuteNode branchExecuteNode,
                                  PlanDraftNode planDraftNode,
                                  ValidateDraftNode validateDraftNode,
                                  TripRiskReasoningNode tripRiskReasoningNode,
                                  PlanRevisionNode planRevisionNode,
                                  ClarifyQuestionNode clarifyQuestionNode,
                                  MergeClarificationNode mergeClarificationNode,
                                  PreClarifyCheckNode preClarifyCheckNode,
                                  FinalizeAnswerNode finalizeAnswerNode,
                                  ConversationStateStore conversationStateStore) {
        this.initStateNode = initStateNode;
        this.retrieveKnowledgeNode = retrieveKnowledgeNode;
        this.branchDispatchNode = branchDispatchNode;
        this.branchExecuteNode = branchExecuteNode;
        this.planDraftNode = planDraftNode;
        this.validateDraftNode = validateDraftNode;
        this.tripRiskReasoningNode = tripRiskReasoningNode;
        this.planRevisionNode = planRevisionNode;
        this.clarifyQuestionNode = clarifyQuestionNode;
        this.mergeClarificationNode = mergeClarificationNode;
        this.preClarifyCheckNode = preClarifyCheckNode;
        this.finalizeAnswerNode = finalizeAnswerNode;
        this.conversationStateStore = conversationStateStore;
    }

    /**
     * 测试兼容构造器。
     *
     * <p>保留第一阶段已有单元测试的构造方式，生产环境由 Spring 使用完整构造器注入真实 Bean。</p>
     */
    LangGraphPlannerFacade(InitStateNode initStateNode,
                           RetrieveKnowledgeNode retrieveKnowledgeNode,
                           PlanDraftNode planDraftNode,
                           ValidateDraftNode validateDraftNode,
                           FinalizeAnswerNode finalizeAnswerNode) {
        this(initStateNode,
                retrieveKnowledgeNode,
                null,
                null,
                planDraftNode,
                validateDraftNode,
                null,
                null,
                new ClarifyQuestionNode(),
                new MergeClarificationNode(),
                new PreClarifyCheckNode(),
                finalizeAnswerNode,
                new InMemoryConversationStateStore());
    }

    /**
     * 测试兼容构造器。
     *
     * <p>保留第二、三阶段测试中手动传入全部基础节点的构造方式；风险审查和自动修正节点为空时，流程保持旧行为。</p>
     */
    LangGraphPlannerFacade(InitStateNode initStateNode,
                           RetrieveKnowledgeNode retrieveKnowledgeNode,
                           BranchDispatchNode branchDispatchNode,
                           BranchExecuteNode branchExecuteNode,
                           PlanDraftNode planDraftNode,
                           ValidateDraftNode validateDraftNode,
                           ClarifyQuestionNode clarifyQuestionNode,
                           MergeClarificationNode mergeClarificationNode,
                           PreClarifyCheckNode preClarifyCheckNode,
                           FinalizeAnswerNode finalizeAnswerNode,
                           ConversationStateStore conversationStateStore) {
        this(initStateNode,
                retrieveKnowledgeNode,
                branchDispatchNode,
                branchExecuteNode,
                planDraftNode,
                validateDraftNode,
                null,
                null,
                clarifyQuestionNode,
                mergeClarificationNode,
                preClarifyCheckNode,
                finalizeAnswerNode,
                conversationStateStore);
    }

    /**
     * 执行支持澄清和分支 Agent 的旅行规划工作流。
     *
     * <p>处理流程：</p>
     * <ol>
     *   <li>根据 sessionId 查询是否存在上一轮 pending 状态。</li>
     *   <li>存在 pending 状态时，把当前用户输入合并进旧任务；否则初始化新状态。</li>
     *   <li>执行 RAG、BranchDispatch、BranchExecute、Planner、Validator、RiskReasoning。</li>
     *   <li>如果 Validator 标记需要澄清，则生成追问并保存 pending 状态。</li>
     *   <li>如果风险审查发现可自动修正问题，进入 PlanRevision 后二次校验和审查。</li>
     *   <li>如果信息足够，则进入 Finalizer 输出最终答案，并清理 pending 状态。</li>
     * </ol>
     *
     * @param request Graph 入口请求，包含用户原始输入、Gatekeeper 路由结果和 sessionId
     * @return Graph 执行结果；失败时包含面向用户的降级答案
     */
    public GraphResult plan(GraphInputRequest request) {
        GraphInputRequest safeRequest = request == null ? new GraphInputRequest() : request;
        String sessionId = normalizeSessionId(safeRequest.getSessionId());
        safeRequest.setSessionId(sessionId);

        try {
            log.info("[Graph] start clarification-capable planning workflow, sessionId={}", sessionId);

            TravelPlanState state = conversationStateStore.findPendingState(sessionId)
                    .map(pendingState -> {
                        log.info("[Graph] resume pending planning workflow, sessionId={}", sessionId);
                        safeRequest.setResumeMode(true);
                        return mergeClarificationNode.merge(pendingState, safeRequest);
                    })
                    .orElseGet(() -> initStateNode.init(safeRequest));

            state = preClarifyCheckNode.check(state);
            if (state.getWorkflowStatus() == WorkflowStatus.NEEDS_CLARIFICATION) {
                state = clarifyQuestionNode.ask(state);
                conversationStateStore.savePendingState(sessionId, state);
                log.info("[Graph] workflow paused before retrieval for clarification, sessionId={}, questions={}",
                        sessionId,
                        state.getPendingQuestions() == null ? 0 : state.getPendingQuestions().size());
                return GraphResult.success(state.getFinalAnswer(), state.getValidationIssues());
            }

            state = retrieveKnowledgeNode.retrieve(state);
            state = runBranchWorkflow(state);
            state = planDraftNode.plan(state);
            state = validateDraftNode.validate(state);

            if (state.getWorkflowStatus() == WorkflowStatus.NEEDS_CLARIFICATION) {
                return pauseForClarification(sessionId, state);
            }

            state = runRiskAndRevisionWorkflow(state);

            if (state.getWorkflowStatus() == WorkflowStatus.NEEDS_CLARIFICATION) {
                return pauseForClarification(sessionId, state);
            }

            state = finalizeAnswerNode.finish(state);
            conversationStateStore.clearPendingState(sessionId);

            log.info("[Graph] workflow finished, sessionId={}, success={}, issues={}",
                    sessionId,
                    state.isSuccess(),
                    state.getValidationIssues() == null ? 0 : state.getValidationIssues().size());

            if (state.isSuccess()) {
                return GraphResult.success(state.getFinalAnswer(), state.getValidationIssues());
            }
            return GraphResult.failure(
                    hasText(state.getFinalAnswer()) ? state.getFinalAnswer() : FAILURE_ANSWER,
                    state.getErrorMessage());

        } catch (Exception e) {
            log.error("[Graph] workflow failed: {}", e.getMessage());
            log.debug("[Graph] workflow failure detail", e);
            return GraphResult.failure(FAILURE_ANSWER, e.getMessage());
        }
    }

    /**
     * 进入澄清追问并保存 pending 状态。
     */
    private GraphResult pauseForClarification(String sessionId, TravelPlanState state) {
        TravelPlanState clarifiedState = clarifyQuestionNode.ask(state);
        conversationStateStore.savePendingState(sessionId, clarifiedState);
        log.info("[Graph] workflow paused for clarification, sessionId={}, questions={}",
                sessionId,
                clarifiedState.getPendingQuestions() == null ? 0 : clarifiedState.getPendingQuestions().size());
        return GraphResult.success(clarifiedState.getFinalAnswer(), clarifiedState.getValidationIssues());
    }

    /**
     * 执行第三阶段分支任务流。
     *
     * <p>生产环境由 Spring 注入真实 BranchDispatchNode / BranchExecuteNode；
     * 包内兼容测试构造器不会注入这两个节点，此时保持第一、二阶段旧测试路径不变。</p>
     */
    private TravelPlanState runBranchWorkflow(TravelPlanState state) {
        if (branchDispatchNode == null || branchExecuteNode == null) {
            return state;
        }
        TravelPlanState dispatchedState = branchDispatchNode.dispatch(state);
        return branchExecuteNode.execute(dispatchedState);
    }

    /**
     * 执行第四阶段风险审查和自动修正闭环。
     *
     * <p>第一版最多自动修正 {@code maxRevisionCount} 次。风险节点或修正节点未注入时保持第三阶段旧行为。</p>
     */
    private TravelPlanState runRiskAndRevisionWorkflow(TravelPlanState state) {
        if (tripRiskReasoningNode == null || planRevisionNode == null) {
            return state;
        }

        TravelPlanState assessedState = tripRiskReasoningNode.assess(state);
        assessedState = applyRiskClarificationIfNeeded(assessedState);
        if (assessedState.getWorkflowStatus() == WorkflowStatus.NEEDS_CLARIFICATION) {
            return assessedState;
        }
        if (needsRevision(assessedState) && canRevise(assessedState)) {
            assessedState = planRevisionNode.revise(assessedState);
            assessedState = validateDraftNode.validate(assessedState);
            if (assessedState.getWorkflowStatus() == WorkflowStatus.NEEDS_CLARIFICATION) {
                return assessedState;
            }
            assessedState = tripRiskReasoningNode.assess(assessedState);
            assessedState = applyRiskClarificationIfNeeded(assessedState);
        }
        return assessedState;
    }

    /**
     * 将风险审查中“必须追问用户”的问题转为 ClarifyQuestionNode 可读取的 ValidationIssue。
     */
    private static TravelPlanState applyRiskClarificationIfNeeded(TravelPlanState state) {
        if (state == null || state.getRiskAssessment() == null || !state.getRiskAssessment().isNeedsClarification()) {
            return state;
        }

        List<ValidationIssue> issues = new ArrayList<>(state.getValidationIssues() == null
                ? List.of()
                : state.getValidationIssues());
        for (RiskIssue issue : state.getRiskAssessment().getIssues()) {
            if (issue != null && issue.isRequiresClarification()) {
                issues.add(ValidationIssue.high(issue.getCode(), issue.getMessage()));
            }
        }
        state.setValidationIssues(issues);
        state.setWorkflowStatus(WorkflowStatus.NEEDS_CLARIFICATION);
        return state;
    }

    private static boolean needsRevision(TravelPlanState state) {
        return state != null
                && state.getRiskAssessment() != null
                && state.getRiskAssessment().isNeedsRevision();
    }

    private static boolean canRevise(TravelPlanState state) {
        return state != null && state.getRevisionCount() < state.getMaxRevisionCount();
    }

    /**
     * 统一会话 ID，保证状态仓库读写使用同一个 key。
     */
    private static String normalizeSessionId(String sessionId) {
        return hasText(sessionId) ? sessionId.trim() : DEFAULT_SESSION_ID;
    }

    /**
     * 判断字符串是否包含非空白文本。
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
