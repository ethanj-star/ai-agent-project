package com.travel.agent.ai.graph;

import com.travel.agent.ai.dto.GatekeeperResponse;
import com.travel.agent.ai.graph.model.GraphInputRequest;
import com.travel.agent.ai.graph.model.GraphResult;
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
import com.travel.agent.ai.graph.node.PreClarifyCheckNode;
import com.travel.agent.ai.graph.node.RetrieveKnowledgeNode;
import com.travel.agent.ai.graph.node.ValidateDraftNode;
import com.travel.agent.ai.graph.store.InMemoryConversationStateStore;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * LangGraphPlannerFacade 的工作流测试。
 *
 * <p>重点验证第一阶段直线流程仍然可用，同时覆盖第二阶段澄清追问、pending 续跑和第三阶段分支接线能力。</p>
 */
class LangGraphPlannerFacadeTest {

    /**
     * 验证 Facade 按 Init -> Retrieve -> Plan -> Validate -> Finalize 的顺序串联节点。
     */
    @Test
    void planRunsLinearWorkflow() {
        InitStateNode init = mock(InitStateNode.class);
        RetrieveKnowledgeNode retrieve = mock(RetrieveKnowledgeNode.class);
        PlanDraftNode plan = mock(PlanDraftNode.class);
        ValidateDraftNode validate = mock(ValidateDraftNode.class);
        FinalizeAnswerNode finalize = mock(FinalizeAnswerNode.class);

        GraphInputRequest request = new GraphInputRequest();
        TravelPlanState state = new TravelPlanState();
        state.setDestinations(List.of("法国"));
        TravelPlanState finalState = new TravelPlanState();
        finalState.setSuccess(true);
        finalState.setFinalAnswer("final answer");
        finalState.setValidationIssues(List.of());

        when(init.init(request)).thenReturn(state);
        when(retrieve.retrieve(state)).thenReturn(state);
        when(plan.plan(state)).thenReturn(state);
        when(validate.validate(state)).thenReturn(state);
        when(finalize.finish(state)).thenReturn(finalState);

        LangGraphPlannerFacade facade =
                new LangGraphPlannerFacade(init, retrieve, plan, validate, finalize);

        GraphResult result = facade.plan(request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAnswer()).isEqualTo("final answer");
        verify(init).init(request);
        verify(retrieve).retrieve(state);
        verify(plan).plan(state);
        verify(validate).validate(state);
        verify(finalize).finish(state);
    }

    /**
     * 验证任一节点抛出异常时，Facade 会返回失败 GraphResult，而不是向上抛出异常。
     */
    @Test
    void planReturnsFailureWhenNodeThrows() {
        InitStateNode init = mock(InitStateNode.class);
        RetrieveKnowledgeNode retrieve = mock(RetrieveKnowledgeNode.class);
        PlanDraftNode plan = mock(PlanDraftNode.class);
        ValidateDraftNode validate = mock(ValidateDraftNode.class);
        FinalizeAnswerNode finalize = mock(FinalizeAnswerNode.class);

        GraphInputRequest request = new GraphInputRequest();
        when(init.init(request)).thenThrow(new RuntimeException("boom"));

        LangGraphPlannerFacade facade =
                new LangGraphPlannerFacade(init, retrieve, plan, validate, finalize);

        GraphResult result = facade.plan(request);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getAnswer()).contains("规划流程暂时遇到问题");
        assertThat(result.getErrorMessage()).contains("boom");
    }

    /**
     * 验证 Validator 标记需要澄清时，Facade 会返回追问并保存 pending 状态，而不是进入 Finalizer。
     */
    @Test
    void planPausesForClarificationAndSavesPendingState() {
        InitStateNode init = mock(InitStateNode.class);
        RetrieveKnowledgeNode retrieve = mock(RetrieveKnowledgeNode.class);
        BranchDispatchNode branchDispatch = mock(BranchDispatchNode.class);
        BranchExecuteNode branchExecute = mock(BranchExecuteNode.class);
        PlanDraftNode plan = mock(PlanDraftNode.class);
        ValidateDraftNode validate = mock(ValidateDraftNode.class);
        FinalizeAnswerNode finalize = mock(FinalizeAnswerNode.class);
        PreClarifyCheckNode precheck = mock(PreClarifyCheckNode.class);
        InMemoryConversationStateStore store = new InMemoryConversationStateStore();

        GraphInputRequest request = new GraphInputRequest();
        request.setSessionId("s1");
        TravelPlanState state = new TravelPlanState();

        when(init.init(request)).thenReturn(state);
        when(precheck.check(state)).thenReturn(state);
        when(retrieve.retrieve(state)).thenReturn(state);
        when(branchDispatch.dispatch(state)).thenReturn(state);
        when(branchExecute.execute(state)).thenReturn(state);
        when(plan.plan(state)).thenReturn(state);
        when(validate.validate(state)).thenAnswer(invocation -> {
            state.setWorkflowStatus(WorkflowStatus.NEEDS_CLARIFICATION);
            state.setValidationIssues(List.of(
                    ValidationIssue.medium("BROAD_DESTINATION", "用户只提供了较宽泛的目的地范围。")));
            return state;
        });

        LangGraphPlannerFacade facade = new LangGraphPlannerFacade(
                init,
                retrieve,
                branchDispatch,
                branchExecute,
                plan,
                validate,
                new ClarifyQuestionNode(),
                new MergeClarificationNode(),
                precheck,
                finalize,
                store);

        GraphResult result = facade.plan(request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAnswer()).contains("你更想去哪些国家或城市");
        assertThat(store.findPendingState("s1")).isPresent();
        verify(finalize, never()).finish(any());
    }

    /**
     * 验证目的地明显过宽时，前置澄清判断会直接追问，并跳过 RAG、Planner 和 Validator。
     */
    @Test
    void planPreClarifiesBroadDestinationBeforeRetrievalAndPlanner() {
        InitStateNode init = mock(InitStateNode.class);
        RetrieveKnowledgeNode retrieve = mock(RetrieveKnowledgeNode.class);
        BranchDispatchNode branchDispatch = mock(BranchDispatchNode.class);
        BranchExecuteNode branchExecute = mock(BranchExecuteNode.class);
        PlanDraftNode plan = mock(PlanDraftNode.class);
        ValidateDraftNode validate = mock(ValidateDraftNode.class);
        FinalizeAnswerNode finalize = mock(FinalizeAnswerNode.class);
        InMemoryConversationStateStore store = new InMemoryConversationStateStore();

        GraphInputRequest request = new GraphInputRequest();
        request.setSessionId("s1");
        TravelPlanState state = new TravelPlanState();
        state.setDestinations(List.of("欧洲"));

        when(init.init(request)).thenReturn(state);

        LangGraphPlannerFacade facade = new LangGraphPlannerFacade(
                init,
                retrieve,
                branchDispatch,
                branchExecute,
                plan,
                validate,
                new ClarifyQuestionNode(),
                new MergeClarificationNode(),
                new PreClarifyCheckNode(),
                finalize,
                store);

        GraphResult result = facade.plan(request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAnswer()).contains("你更想去哪些国家或城市");
        assertThat(store.findPendingState("s1")).isPresent();
        verify(retrieve, never()).retrieve(any());
        verify(branchDispatch, never()).dispatch(any());
        verify(branchExecute, never()).execute(any());
        verify(plan, never()).plan(any());
        verify(validate, never()).validate(any());
        verify(finalize, never()).finish(any());
    }

    /**
     * 验证第三阶段分支节点会在 RAG 之后、Planner 之前执行。
     */
    @Test
    void planRunsBranchWorkflowBetweenRetrieveAndPlanner() {
        InitStateNode init = mock(InitStateNode.class);
        RetrieveKnowledgeNode retrieve = mock(RetrieveKnowledgeNode.class);
        BranchDispatchNode branchDispatch = mock(BranchDispatchNode.class);
        BranchExecuteNode branchExecute = mock(BranchExecuteNode.class);
        PlanDraftNode plan = mock(PlanDraftNode.class);
        ValidateDraftNode validate = mock(ValidateDraftNode.class);
        FinalizeAnswerNode finalize = mock(FinalizeAnswerNode.class);
        PreClarifyCheckNode precheck = mock(PreClarifyCheckNode.class);
        InMemoryConversationStateStore store = new InMemoryConversationStateStore();

        GraphInputRequest request = new GraphInputRequest();
        request.setSessionId("branch-s1");
        TravelPlanState state = new TravelPlanState();
        TravelPlanState finalState = new TravelPlanState();
        finalState.setSuccess(true);
        finalState.setFinalAnswer("branch final answer");
        finalState.setValidationIssues(List.of());

        when(init.init(request)).thenReturn(state);
        when(precheck.check(state)).thenReturn(state);
        when(retrieve.retrieve(state)).thenReturn(state);
        when(branchDispatch.dispatch(state)).thenReturn(state);
        when(branchExecute.execute(state)).thenReturn(state);
        when(plan.plan(state)).thenReturn(state);
        when(validate.validate(state)).thenReturn(state);
        when(finalize.finish(state)).thenReturn(finalState);

        LangGraphPlannerFacade facade = new LangGraphPlannerFacade(
                init,
                retrieve,
                branchDispatch,
                branchExecute,
                plan,
                validate,
                new ClarifyQuestionNode(),
                new MergeClarificationNode(),
                precheck,
                finalize,
                store);

        GraphResult result = facade.plan(request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAnswer()).isEqualTo("branch final answer");
        InOrder order = inOrder(init, precheck, retrieve, branchDispatch, branchExecute, plan, validate, finalize);
        order.verify(init).init(request);
        order.verify(precheck).check(state);
        order.verify(retrieve).retrieve(state);
        order.verify(branchDispatch).dispatch(state);
        order.verify(branchExecute).execute(state);
        order.verify(plan).plan(state);
        order.verify(validate).validate(state);
        order.verify(finalize).finish(state);
    }

    /**
     * 验证同一 session 下存在 pending 状态时，Facade 会合并用户补充信息，最终完成后清理 pending 状态。
     */
    @Test
    void planResumesPendingStateAndClearsItAfterCompletion() {
        InitStateNode init = mock(InitStateNode.class);
        RetrieveKnowledgeNode retrieve = mock(RetrieveKnowledgeNode.class);
        BranchDispatchNode branchDispatch = mock(BranchDispatchNode.class);
        BranchExecuteNode branchExecute = mock(BranchExecuteNode.class);
        PlanDraftNode plan = mock(PlanDraftNode.class);
        ValidateDraftNode validate = mock(ValidateDraftNode.class);
        FinalizeAnswerNode finalize = mock(FinalizeAnswerNode.class);
        InMemoryConversationStateStore store = new InMemoryConversationStateStore();

        TravelPlanState pending = new TravelPlanState();
        pending.setSessionId("s1");
        pending.setUserQuery("我想下个月去欧洲玩，帮我安排");
        pending.setTravelTime("下个月");
        pending.setWorkflowStatus(WorkflowStatus.NEEDS_CLARIFICATION);
        store.savePendingState("s1", pending);

        when(retrieve.retrieve(any(TravelPlanState.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(branchDispatch.dispatch(any(TravelPlanState.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(branchExecute.execute(any(TravelPlanState.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(plan.plan(any(TravelPlanState.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(validate.validate(any(TravelPlanState.class))).thenAnswer(invocation -> {
            TravelPlanState state = invocation.getArgument(0);
            state.setWorkflowStatus(WorkflowStatus.PLANNING);
            return state;
        });
        when(finalize.finish(any(TravelPlanState.class))).thenAnswer(invocation -> {
            TravelPlanState state = invocation.getArgument(0);
            assertThat(state.getTravelTime()).isEqualTo("下个月");
            assertThat(state.getDurationDays()).isEqualTo(10);
            assertThat(state.getDurationText()).isEqualTo("10天");
            state.setSuccess(true);
            state.setFinalAnswer(state.getUserQuery());
            state.setWorkflowStatus(WorkflowStatus.COMPLETED);
            return state;
        });

        LangGraphPlannerFacade facade = new LangGraphPlannerFacade(
                init,
                retrieve,
                branchDispatch,
                branchExecute,
                plan,
                validate,
                new ClarifyQuestionNode(),
                new MergeClarificationNode(),
                new PreClarifyCheckNode(),
                finalize,
                store);

        GraphInputRequest request = new GraphInputRequest();
        request.setSessionId("s1");
        request.setUserQuery("法国和意大利，10天，预算1200欧");
        GatekeeperResponse route = new GatekeeperResponse();
        GatekeeperResponse.Entities entities = new GatekeeperResponse.Entities();
        entities.setLocations(List.of("法国", "意大利"));
        entities.setTime("10天");
        entities.setKeywords(List.of("预算1200欧"));
        route.setEntities(entities);
        request.setRoute(route);

        GraphResult result = facade.plan(request);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAnswer()).contains("用户补充信息：法国和意大利");
        assertThat(store.findPendingState("s1")).isEmpty();
        verify(init, never()).init(any());
    }
}
