package com.travel.agent.ai.rag;

import com.travel.agent.ai.agents.RagQueryClassifierAgent;
import com.travel.agent.ai.graph.model.RagQueryType;
import com.travel.agent.ai.graph.model.RagRetrievalResult;
import com.travel.agent.ai.graph.model.RagRetrievalStrategy;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.tools.KnowledgeTools;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AdaptiveRagService 的单元测试。
 *
 * <p>重点验证“查询类型 -> 检索策略 -> planned query”的映射关系。
 * Pinecone 命中内容在这里用空列表模拟，避免单元测试访问真实向量库。</p>
 */
class AdaptiveRagServiceTest {

    @Test
    void comparativeQuestionUsesComparativeStrategy() {
        KnowledgeTools tools = mock(KnowledgeTools.class);
        when(tools.searchTravelGuideDocuments(anyString(), anyInt())).thenReturn(List.of());
        AdaptiveRagService service = new AdaptiveRagService(new RagQueryClassifierAgent(), tools);

        TravelPlanState state = new TravelPlanState();
        state.setUserQuery("巴黎和尼斯哪个更适合亲子？");
        state.setDestinations(List.of("巴黎", "尼斯"));

        RagRetrievalResult result = service.retrieve(state);

        assertThat(result.getDecision().getQueryType()).isEqualTo(RagQueryType.COMPARATIVE);
        assertThat(result.getDecision().getRetrievalStrategy())
                .isEqualTo(RagRetrievalStrategy.COMPARATIVE_MULTI_SOURCE);
        assertThat(result.getDecision().getPlannedQueries()).hasSizeGreaterThanOrEqualTo(2);
    }

    @Test
    void multiHopPlanUsesMultiStageQueries() {
        KnowledgeTools tools = mock(KnowledgeTools.class);
        when(tools.searchTravelGuideDocuments(anyString(), anyInt())).thenReturn(List.of());
        AdaptiveRagService service = new AdaptiveRagService(new RagQueryClassifierAgent(), tools);

        TravelPlanState state = new TravelPlanState();
        state.setUserQuery("国庆去法国和意大利玩10天，预算1200欧，想避开人多。");
        state.setDestinations(List.of("法国", "意大利"));
        state.setDurationDays(10);
        state.setDurationText("10天");

        RagRetrievalResult result = service.retrieve(state);

        assertThat(result.getDecision().getQueryType()).isEqualTo(RagQueryType.MULTI_HOP);
        assertThat(result.getDecision().getRetrievalStrategy())
                .isEqualTo(RagRetrievalStrategy.MULTI_STAGE_CONTEXT);
        assertThat(result.getDecision().getPlannedQueries())
                .anyMatch(query -> query.contains("路线"))
                .anyMatch(query -> query.contains("预算"));
    }
}
