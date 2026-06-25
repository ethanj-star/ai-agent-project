package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.AdaptiveRagDecision;
import com.travel.agent.ai.graph.model.RagQueryType;
import com.travel.agent.ai.graph.model.RagRetrievalResult;
import com.travel.agent.ai.graph.model.RagRetrievalStrategy;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.rag.AdaptiveRagService;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * AdaptiveRagNode 的单元测试。
 *
 * <p>重点验证第 14 阶段 Graph 接线：Adaptive RAG 成功时写回状态，
 * 失败时回退旧 RetrieveKnowledgeNode，而不是让整个规划流程中断。</p>
 */
class AdaptiveRagNodeTest {

    @Test
    void retrieveWritesDecisionResultAndContext() {
        AdaptiveRagService service = mock(AdaptiveRagService.class);
        RetrieveKnowledgeNode fallback = mock(RetrieveKnowledgeNode.class);
        TravelPlanState state = new TravelPlanState();

        AdaptiveRagDecision decision = new AdaptiveRagDecision();
        decision.setQueryType(RagQueryType.EXPLORATORY);
        decision.setRetrievalStrategy(RagRetrievalStrategy.SEMANTIC_EXPANSION);
        decision.setPlannedQueries(List.of("法国 小众 推荐"));

        RagRetrievalResult result = new RagRetrievalResult();
        result.setDecision(decision);
        result.setContext("Adaptive context");
        result.setExecutedQueries(List.of("法国 小众 推荐"));
        result.setHitCount(2);
        when(service.retrieve(state)).thenReturn(result);

        AdaptiveRagNode node = new AdaptiveRagNode(service, fallback);
        TravelPlanState output = node.retrieve(state);

        assertThat(output.getRagContext()).isEqualTo("Adaptive context");
        assertThat(output.getAdaptiveRagDecision().getQueryType()).isEqualTo(RagQueryType.EXPLORATORY);
        assertThat(output.getRagTraceSummary()).contains("SEMANTIC_EXPANSION");
    }

    @Test
    void retrieveFallsBackToLegacyNodeWhenAdaptiveFails() {
        AdaptiveRagService service = mock(AdaptiveRagService.class);
        RetrieveKnowledgeNode fallback = mock(RetrieveKnowledgeNode.class);
        TravelPlanState state = new TravelPlanState();
        TravelPlanState fallbackState = new TravelPlanState();
        fallbackState.setRagContext("legacy fallback context");

        when(service.retrieve(state)).thenThrow(new RuntimeException("pinecone down"));
        when(fallback.retrieve(state)).thenReturn(fallbackState);

        AdaptiveRagNode node = new AdaptiveRagNode(service, fallback);
        TravelPlanState output = node.retrieve(state);

        assertThat(output.getRagContext()).isEqualTo("legacy fallback context");
        assertThat(state.getAdaptiveRagDecision().isFallbackRequired()).isTrue();
        verify(fallback).retrieve(state);
    }
}
