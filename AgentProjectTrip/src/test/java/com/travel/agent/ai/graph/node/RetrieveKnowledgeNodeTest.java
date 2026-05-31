package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.tools.KnowledgeTools;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * RetrieveKnowledgeNode 的单元测试。
 *
 * <p>重点验证 RAG 检索结果写入状态，以及 KnowledgeTools / Pinecone 异常时的兜底上下文。</p>
 */
class RetrieveKnowledgeNodeTest {

    /**
     * 工具正常返回时，节点应把检索到的攻略写入 ragContext。
     */
    @Test
    void retrieveWritesRagContext() {
        KnowledgeTools tools = mock(KnowledgeTools.class);
        when(tools.searchTravelGuide(contains("法国"))).thenReturn("检索到的参考攻略：巴黎防坑经验");

        RetrieveKnowledgeNode node = new RetrieveKnowledgeNode(tools);
        TravelPlanState state = new TravelPlanState();
        state.setUserQuery("法国10天怎么玩");
        state.setDestinations(List.of("法国"));
        state.setTravelTime("国庆节");
        state.setKeywords(List.of("10天"));

        TravelPlanState result = node.retrieve(state);

        assertThat(result.getRagContext()).contains("巴黎防坑经验");
    }

    /**
     * 工具抛异常时，节点应写入兜底文本，保证后续 Planner 仍可执行。
     */
    @Test
    void retrieveFallsBackWhenToolFails() {
        KnowledgeTools tools = mock(KnowledgeTools.class);
        when(tools.searchTravelGuide(org.mockito.ArgumentMatchers.anyString()))
                .thenThrow(new RuntimeException("pinecone down"));

        RetrieveKnowledgeNode node = new RetrieveKnowledgeNode(tools);
        TravelPlanState state = new TravelPlanState();

        TravelPlanState result = node.retrieve(state);

        assertThat(result.getRagContext()).contains("私有知识库暂时不可用");
    }
}
