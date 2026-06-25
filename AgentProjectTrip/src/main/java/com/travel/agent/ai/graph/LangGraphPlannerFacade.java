package com.travel.agent.ai.graph;

import com.travel.agent.ai.graph.model.GraphInputRequest;
import com.travel.agent.ai.graph.model.GraphResult;
import com.travel.agent.ai.graph.model.BranchDispatchDecision;
import com.travel.agent.ai.graph.model.RiskIssue;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.ValidationIssue;
import com.travel.agent.ai.graph.model.WorkflowStatus;
import com.travel.agent.ai.graph.node.BranchDispatchNode;
import com.travel.agent.ai.graph.node.BranchDispatchGuardNode;
import com.travel.agent.ai.graph.node.BranchExecuteNode;
import com.travel.agent.ai.graph.node.ClarifyQuestionNode;
import com.travel.agent.ai.graph.node.AdaptiveRagNode;
import com.travel.agent.ai.graph.node.FinalizeAnswerNode;
import com.travel.agent.ai.graph.node.InitStateNode;
import com.travel.agent.ai.graph.node.MergeClarificationNode;
import com.travel.agent.ai.graph.node.ModelBranchDispatchNode;
import com.travel.agent.ai.graph.node.PlanDraftNode;
import com.travel.agent.ai.graph.node.PlanRevisionNode;
import com.travel.agent.ai.graph.node.PreClarifyCheckNode;
import com.travel.agent.ai.graph.node.RetrieveKnowledgeNode;
import com.travel.agent.ai.graph.node.TripRiskReasoningNode;
import com.travel.agent.ai.graph.node.ValidateDraftNode;
import com.travel.agent.ai.graph.store.ConversationStateStore;
import com.travel.agent.ai.graph.store.InMemoryConversationStateStore;
import com.travel.agent.ai.memory.UserMemoryService;
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
 *   <li>在第十三阶段支持“核心模型建议分支任务 -> Java Guard 校验 -> 分支 Agent 执行”的模型派发闭环。</li>
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

    /** 从私有知识库检索 RAG 上下文的旧节点；第十四阶段之后作为 fallback 保留。 */
    private final RetrieveKnowledgeNode retrieveKnowledgeNode;

    /** 第十四阶段 Adaptive RAG 节点；测试构造器可为空，生产环境由 Spring setter 注入。 */
    private AdaptiveRagNode adaptiveRagNode;

    /** 根据状态生成天气、景点、航班等分支任务的节点。 */
    private final BranchDispatchNode branchDispatchNode;

    /** 第十三阶段模型分支派发节点；测试构造器可为空，生产环境由 Spring setter 注入。 */
    private ModelBranchDispatchNode modelBranchDispatchNode;

    /** 第十三阶段分支派发安全守卫；测试构造器可为空，生产环境由 Spring setter 注入。 */
    private BranchDispatchGuardNode branchDispatchGuardNode;

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

    /** 第七阶段用户记忆服务；测试构造器可为空，生产环境由 Spring setter 注入。 */
    private UserMemoryService userMemoryService;

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
     * 注入用户记忆服务。
     *
     * <p>使用 setter 而不是主构造器，是为了保持旧单元测试构造器稳定。
     * 没有记忆服务时 Graph 仍按前六阶段流程运行。</p>
     *
     * @param userMemoryService 用户记忆服务
     */
    @Autowired(required = false)
    public void setUserMemoryService(UserMemoryService userMemoryService) {
        this.userMemoryService = userMemoryService;
    }

    /**
     * 注入第十三阶段模型派发节点。
     *
     * <p>使用可选 setter 是为了保持前几个阶段的测试构造器稳定。
     * 未注入时，Graph 会继续使用旧的 {@link BranchDispatchNode} 规则派发。</p>
     *
     * @param modelBranchDispatchNode DeepSeek Pro 分支任务建议节点
     */
    @Autowired(required = false)
    public void setModelBranchDispatchNode(ModelBranchDispatchNode modelBranchDispatchNode) {
        this.modelBranchDispatchNode = modelBranchDispatchNode;
    }

    /**
     * 注入第十三阶段 Java Guard。
     *
     * <p>Guard 是模型派发的安全边界；未注入时，Facade 不会使用模型建议，
     * 而是回退第三阶段的规则派发流程。</p>
     *
     * @param branchDispatchGuardNode 模型建议校验节点
     */
    @Autowired(required = false)
    public void setBranchDispatchGuardNode(BranchDispatchGuardNode branchDispatchGuardNode) {
        this.branchDispatchGuardNode = branchDispatchGuardNode;
    }

    /**
     * 注入第十四阶段 Adaptive RAG 节点。
     *
     * <p>使用可选 setter 是为了保持旧单元测试构造器稳定。未注入时，Graph 会继续使用
     * {@link RetrieveKnowledgeNode} 的固定检索流程。</p>
     *
     * @param adaptiveRagNode 自适应 RAG 检索节点
     */
    @Autowired(required = false)
    public void setAdaptiveRagNode(AdaptiveRagNode adaptiveRagNode) {
        this.adaptiveRagNode = adaptiveRagNode;
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
        // 外部调用者理论上应该传 request，但这里仍做兜底，避免 null 导致整个接口 500。
        GraphInputRequest safeRequest = request == null ? new GraphInputRequest() : request;

        // sessionId 是“这轮对话”的钥匙：后面要靠它找到上一轮尚未完成的 pending 任务。
        String sessionId = normalizeSessionId(safeRequest.getSessionId());
        safeRequest.setSessionId(sessionId);

        try {
            log.info("[Graph] start clarification-capable planning workflow, sessionId={}", sessionId);

            // Step 1：先判断这是不是“上一轮追问后的补充回答”。
            //
            // 例如：
            //   第一轮：用户说“帮我规划欧洲旅行”
            //   系统追问：“你想去几天？”
            //   第二轮：用户说“10天”
            //
            // 第二轮的“10天”不能当作新任务处理，必须合并回上一轮 pendingState。
            TravelPlanState state = conversationStateStore.findPendingState(sessionId)
                    .map(pendingState -> {
                        log.info("[Graph] resume pending planning workflow, sessionId={}", sessionId);
                        // 标记为续跑模式，后续节点可以知道当前输入是在补充旧任务。
                        safeRequest.setResumeMode(true);
                        return mergeClarificationNode.merge(pendingState, safeRequest);
                    })
                    .orElseGet(() -> initStateNode.init(safeRequest));

            // Step 2：把用户长期偏好等记忆上下文写入状态，供 Planner 生成方案时参考。
            // 这一步只补充参考信息，不覆盖用户本次明确输入。
            state = attachUserMemoryContext(state, sessionId);

            // Step 3：前置澄清检查。
            // 如果用户输入太模糊，先追问，不要浪费成本去查 RAG、调工具或调用大模型生成草稿。
            state = preClarifyCheckNode.check(state);
            if (state.getWorkflowStatus() == WorkflowStatus.NEEDS_CLARIFICATION) {
                // 把缺失信息转换成自然语言问题，例如“你计划出行几天？”
                state = clarifyQuestionNode.ask(state);

                // 保存当前状态，等用户下一轮回答后，可以从这里继续跑。
                conversationStateStore.savePendingState(sessionId, state);
                log.info("[Graph] workflow paused before retrieval for clarification, sessionId={}, questions={}",
                        sessionId,
                        state.getPendingQuestions() == null ? 0 : state.getPendingQuestions().size());

                // 这里的 success 表示“本次请求被成功处理并生成追问”，不是最终旅行方案已经完成。
                return GraphResult.success(state.getFinalAnswer(), state.getValidationIssues());
            }

            // Step 4：信息足够后，先从知识库检索旅行攻略、防坑信息等 RAG 上下文。
            // 第十四阶段优先使用 AdaptiveRagNode，根据问题类型选择不同检索策略；
            // 未注入或失败时保留旧 RetrieveKnowledgeNode，避免知识库增强影响主流程稳定性。
            state = runRagWorkflow(state);

            // Step 5：派发并执行分支 Agent 任务，例如天气、景点、航班、知识库补充等。
            // 分支结果会写回 state，供后面的 Planner 使用。
            state = runBranchWorkflow(state);

            // Step 6：调用 Planner 生成第一版旅行规划草稿。
            state = planDraftNode.plan(state);

            // Step 7：校验草稿是否满足用户约束，或者是否仍缺少必须信息。
            state = validateDraftNode.validate(state);

            // Validator 如果发现必须问用户的问题，就暂停流程，保存 pending 状态。
            if (state.getWorkflowStatus() == WorkflowStatus.NEEDS_CLARIFICATION) {
                return pauseForClarification(sessionId, state);
            }

            // Step 8：风险审查和自动修正。
            // 例如检查天气冲突、预算冲突、行程过满等；可自动修正时会让 Planner 重写一版。
            state = runRiskAndRevisionWorkflow(state);

            // 风险审查也可能发现必须由用户决定的问题，这时同样进入澄清暂停。
            if (state.getWorkflowStatus() == WorkflowStatus.NEEDS_CLARIFICATION) {
                return pauseForClarification(sessionId, state);
            }

            // Step 9：所有信息都齐了，把草稿、工具结果、风险提示整理成最终 Markdown 答案。
            state = finalizeAnswerNode.finish(state);

            // 最终答案已经生成，本轮任务结束，清理 pending 状态，避免下一次对话误续跑旧任务。
            conversationStateStore.clearPendingState(sessionId);

            log.info("[Graph] workflow finished, sessionId={}, success={}, issues={}",
                    sessionId,
                    state.isSuccess(),
                    state.getValidationIssues() == null ? 0 : state.getValidationIssues().size());

            // 正常成功：把最终答案和校验问题一起返回给上层 MastermindAgent / Controller。
            if (state.isSuccess()) {
                return GraphResult.success(state.getFinalAnswer(), state.getValidationIssues());
            }

            // 节点没有抛异常，但状态标记为失败：优先返回已有可读答案，否则返回统一兜底文案。
            return GraphResult.failure(
                    hasText(state.getFinalAnswer()) ? state.getFinalAnswer() : FAILURE_ANSWER,
                    state.getErrorMessage());

        } catch (Exception e) {
            // 最外层兜底：任何节点异常都被包成 GraphResult，避免异常直接穿透到 Web 层。
            log.error("[Graph] workflow failed: {}", e.getMessage());
            log.debug("[Graph] workflow failure detail", e);
            return GraphResult.failure(FAILURE_ANSWER, e.getMessage());
        }
    }

    /**
     * 将用户记忆摘要写入 Graph 状态。
     *
     * <p>记忆只在 Planner prompt 中作为参考偏好出现，不改变 TravelRequirementSpec 和 Gatekeeper 实体，
     * 因此不会覆盖用户本次明确输入。</p>
     */
    private TravelPlanState attachUserMemoryContext(TravelPlanState state, String sessionId) {
        // 测试环境或未启用记忆模块时 userMemoryService 可能为空，直接跳过即可。
        if (state == null || userMemoryService == null) {
            return state;
        }

        // 生成一段适合放进 Planner prompt 的记忆摘要，而不是直接改写用户需求。
        state.setUserMemoryContext(userMemoryService.buildPromptContext(null, sessionId));
        return state;
    }

    /**
     * 进入澄清追问并保存 pending 状态。
     */
    private GraphResult pauseForClarification(String sessionId, TravelPlanState state) {
        // 把 ValidationIssue / RiskIssue 这类机器可读问题，转换成用户能看懂的追问文本。
        TravelPlanState clarifiedState = clarifyQuestionNode.ask(state);

        // 保存暂停状态。用户下一轮回答时，plan() 开头会通过 sessionId 找回它并继续执行。
        conversationStateStore.savePendingState(sessionId, clarifiedState);
        log.info("[Graph] workflow paused for clarification, sessionId={}, questions={}",
                sessionId,
                clarifiedState.getPendingQuestions() == null ? 0 : clarifiedState.getPendingQuestions().size());

        // 这里返回 success 是因为“追问已成功生成”；真正的最终方案要等用户补充后再生成。
        return GraphResult.success(clarifiedState.getFinalAnswer(), clarifiedState.getValidationIssues());
    }

    /**
     * 执行分支任务流。
     *
     * <p>第十三阶段优先使用“ModelBranchDispatchNode -> BranchDispatchGuardNode”的模型派发链路；
     * 如果新节点未注入，继续使用第三阶段旧的 BranchDispatchNode 规则派发。
     * 包内兼容测试构造器不会注入这些节点，此时保持第一、二阶段旧测试路径不变。</p>
     */
    private TravelPlanState runBranchWorkflow(TravelPlanState state) {
        // 兼容旧测试构造器：旧测试没有注入执行节点时，直接跳过分支流程。
        if (branchExecuteNode == null) {
            return state;
        }

        TravelPlanState dispatchedState;
        if (modelBranchDispatchNode != null && branchDispatchGuardNode != null) {
            // 第十三阶段：模型负责建议，Guard 负责边界。模型失败时 Guard 会回退旧规则派发。
            BranchDispatchDecision decision = modelBranchDispatchNode.dispatch(state);
            dispatchedState = branchDispatchGuardNode.guard(state, decision);
        } else if (branchDispatchNode != null) {
            // 第三阶段兜底：没有模型派发节点时，继续使用确定性 Java 规则派发。
            dispatchedState = branchDispatchNode.dispatch(state);
        } else {
            return state;
        }

        // Execute 才真正调用 BranchAgentFacade / Tools，并把结果写回 state。
        return branchExecuteNode.execute(dispatchedState);
    }

    /**
     * 执行 RAG 检索阶段。
     *
     * <p>第十四阶段优先使用 Adaptive RAG。旧 RetrieveKnowledgeNode 仍保留为 fallback，
     * 因为当前 MediaCrawler 知识运营、metadata filter 和 reranker 还在 TODO 队列中，
     * 主规划流程不能依赖新 RAG 一次性完全稳定。</p>
     */
    private TravelPlanState runRagWorkflow(TravelPlanState state) {
        if (adaptiveRagNode != null) {
            return adaptiveRagNode.retrieve(state);
        }
        return retrieveKnowledgeNode.retrieve(state);
    }

    /**
     * 执行第四阶段风险审查和自动修正闭环。
     *
     * <p>第一版最多自动修正 {@code maxRevisionCount} 次。风险节点或修正节点未注入时保持第三阶段旧行为。</p>
     */
    private TravelPlanState runRiskAndRevisionWorkflow(TravelPlanState state) {
        // 兼容旧测试或未启用第四阶段节点的场景：没有风险节点就保持旧流程。
        if (tripRiskReasoningNode == null || planRevisionNode == null) {
            return state;
        }

        // 先对当前草稿做风险审查，判断是否有冲突、过载、预算不匹配等问题。
        TravelPlanState assessedState = tripRiskReasoningNode.assess(state);

        // 如果风险节点认为必须追问用户，把风险问题转换成澄清问题。
        assessedState = applyRiskClarificationIfNeeded(assessedState);
        if (assessedState.getWorkflowStatus() == WorkflowStatus.NEEDS_CLARIFICATION) {
            return assessedState;
        }

        // 如果问题可以自动修正，且还没超过最大修正次数，就进入 Revision。
        if (needsRevision(assessedState) && canRevise(assessedState)) {
            assessedState = planRevisionNode.revise(assessedState);

            // 修正后重新校验，防止新草稿又产生新的硬性信息缺口。
            assessedState = validateDraftNode.validate(assessedState);
            if (assessedState.getWorkflowStatus() == WorkflowStatus.NEEDS_CLARIFICATION) {
                return assessedState;
            }

            // 修正后的草稿再做一次风险审查，确认自动修正是否真的解决问题。
            assessedState = tripRiskReasoningNode.assess(assessedState);
            assessedState = applyRiskClarificationIfNeeded(assessedState);
        }
        return assessedState;
    }

    /**
     * 将风险审查中“必须追问用户”的问题转为 ClarifyQuestionNode 可读取的 ValidationIssue。
     */
    private static TravelPlanState applyRiskClarificationIfNeeded(TravelPlanState state) {
        // 没有风险审查结果，或者风险节点没有要求追问用户，就不改变状态。
        if (state == null || state.getRiskAssessment() == null || !state.getRiskAssessment().isNeedsClarification()) {
            return state;
        }

        // ClarifyQuestionNode 读取的是 validationIssues，所以这里把风险问题转成校验问题。
        List<ValidationIssue> issues = new ArrayList<>(state.getValidationIssues() == null
                ? List.of()
                : state.getValidationIssues());
        for (RiskIssue issue : state.getRiskAssessment().getIssues()) {
            if (issue != null && issue.isRequiresClarification()) {
                issues.add(ValidationIssue.high(issue.getCode(), issue.getMessage()));
            }
        }

        // 标记为需要澄清后，plan() 主流程会调用 pauseForClarification() 暂停并追问。
        state.setValidationIssues(issues);
        state.setWorkflowStatus(WorkflowStatus.NEEDS_CLARIFICATION);
        return state;
    }

    private static boolean needsRevision(TravelPlanState state) {
        // 风险节点会设置 needsRevision，表示“这个草稿可以由系统自动改一版”。
        return state != null
                && state.getRiskAssessment() != null
                && state.getRiskAssessment().isNeedsRevision();
    }

    private static boolean canRevise(TravelPlanState state) {
        // 防止自动修正无限循环：只能在 revisionCount 小于 maxRevisionCount 时继续重写。
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
