package com.travel.agent.ai.graph;

import com.travel.agent.ai.graph.model.GraphInputRequest;
import com.travel.agent.ai.graph.model.GraphResult;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.WorkflowStatus;
import com.travel.agent.ai.graph.node.ClarifyQuestionNode;
import com.travel.agent.ai.graph.node.FinalizeAnswerNode;
import com.travel.agent.ai.graph.node.InitStateNode;
import com.travel.agent.ai.graph.node.MergeClarificationNode;
import com.travel.agent.ai.graph.node.PlanDraftNode;
import com.travel.agent.ai.graph.node.PreClarifyCheckNode;
import com.travel.agent.ai.graph.node.RetrieveKnowledgeNode;
import com.travel.agent.ai.graph.node.ValidateDraftNode;
import com.travel.agent.ai.graph.store.ConversationStateStore;
import com.travel.agent.ai.graph.store.InMemoryConversationStateStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

/**
 * 旅行规划 Graph 工作流门面。
 *
 * <p>系统架构位置：MastermindAgent -> <b>LangGraphPlannerFacade</b> -> Graph Nodes</p>
 *
 * <p>职责：
 * <ul>
 *   <li>作为 Spring AI 外围调度层和后续 LangGraph4j 状态图之间的稳定边界。</li>
 *   <li>串联 Init、RAG、Planner、Validator、Clarify、Finalizer 等节点。</li>
 *   <li>在第二阶段支持“信息不足 -> 追问用户 -> 保存 pending 状态 -> 用户补充后续跑”的闭环。</li>
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

    /** 调用核心模型生成 PlannerDraft 的节点。 */
    private final PlanDraftNode planDraftNode;

    /** 基于规则校验草案质量和信息缺口的节点。 */
    private final ValidateDraftNode validateDraftNode;

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
     * 构造器注入第二阶段工作流需要的全部节点。
     *
     * <p>后续迁移到 LangGraph4j {@code StateGraph} 时，对外仍保持 {@link #plan(GraphInputRequest)} 方法不变。</p>
     */
    @Autowired
    public LangGraphPlannerFacade(InitStateNode initStateNode,
                                  RetrieveKnowledgeNode retrieveKnowledgeNode,
                                  PlanDraftNode planDraftNode,
                                  ValidateDraftNode validateDraftNode,
                                  ClarifyQuestionNode clarifyQuestionNode,
                                  MergeClarificationNode mergeClarificationNode,
                                  PreClarifyCheckNode preClarifyCheckNode,
                                  FinalizeAnswerNode finalizeAnswerNode,
                                  ConversationStateStore conversationStateStore) {
        this.initStateNode = initStateNode;
        this.retrieveKnowledgeNode = retrieveKnowledgeNode;
        this.planDraftNode = planDraftNode;
        this.validateDraftNode = validateDraftNode;
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
                planDraftNode,
                validateDraftNode,
                new ClarifyQuestionNode(),
                new MergeClarificationNode(),
                new PreClarifyCheckNode(),
                finalizeAnswerNode,
                new InMemoryConversationStateStore());
    }

    /**
     * 执行第二阶段旅行规划工作流。
     *
     * <p>处理流程：</p>
     * <ol>
     *   <li>根据 sessionId 查询是否存在上一轮 pending 状态。</li>
     *   <li>存在 pending 状态时，把当前用户输入合并进旧任务；否则初始化新状态。</li>
     *   <li>执行 RAG、Planner、Validator。</li>
     *   <li>如果 Validator 标记需要澄清，则生成追问并保存 pending 状态。</li>
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
            state = planDraftNode.plan(state);
            state = validateDraftNode.validate(state);

            if (state.getWorkflowStatus() == WorkflowStatus.NEEDS_CLARIFICATION) {
                state = clarifyQuestionNode.ask(state);
                conversationStateStore.savePendingState(sessionId, state);
                log.info("[Graph] workflow paused for clarification, sessionId={}, questions={}",
                        sessionId,
                        state.getPendingQuestions() == null ? 0 : state.getPendingQuestions().size());
                return GraphResult.success(state.getFinalAnswer(), state.getValidationIssues());
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
