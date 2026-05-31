package com.travel.agent.ai.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.ai.graph.LangGraphPlannerFacade;
import com.travel.agent.ai.graph.model.GraphResult;
import com.travel.agent.ai.tools.FlightTools;
import com.travel.agent.ai.tools.KnowledgeTools;
import com.travel.agent.ai.tools.PlacesTools;
import com.travel.agent.ai.tools.WeatherTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * MastermindAgent 的路由集成测试。
 *
 * <p>重点验证 Gatekeeper 判定为 {@code PLAN_OR_RAG} 时，MastermindAgent 会进入
 * 第一阶段直线规划黑箱，而不是继续走旧的一次性核心模型回答。</p>
 */
class MastermindAgentTest {

    /**
     * 验证 DIRECT_CHAT 会优先返回 Gatekeeper 已经生成的 direct_reply，而不是覆盖成固定欢迎语。
     */
    @Test
    void directChatReturnsGatekeeperDirectReply() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));

        GatekeeperAgent gatekeeperAgent = mock(GatekeeperAgent.class);
        LangGraphPlannerFacade plannerFacade = mock(LangGraphPlannerFacade.class);
        ChatModel branchChatModel = mock(ChatModel.class);

        MastermindAgent agent = new MastermindAgent(
                builder,
                mock(FlightTools.class),
                mock(WeatherTools.class),
                mock(PlacesTools.class),
                mock(KnowledgeTools.class),
                gatekeeperAgent,
                new ObjectMapper(),
                plannerFacade,
                branchChatModel);

        when(gatekeeperAgent.routeRequest("欧洲有几个国家？"))
                .thenReturn("""
                        {"intent":"DIRECT_CHAT","entities":{"locations":[],"time":null,"keywords":["欧洲","国家数量"]},"direct_reply":"欧洲目前通常按44个主权国家来统计。"}
                        """);

        String answer = agent.handleUserWorkflow("欧洲有几个国家？");

        assertThat(answer).isEqualTo("欧洲目前通常按44个主权国家来统计。");
        verify(plannerFacade, never()).plan(any());
    }

    /**
     * 验证复杂规划意图会调用 LangGraphPlannerFacade。
     *
     * <p>测试中 mock Gatekeeper 和 Facade，避免触发真实模型与 RAG。</p>
     */
    @Test
    void planOrRagIntentCallsPlannerFacade() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));

        GatekeeperAgent gatekeeperAgent = mock(GatekeeperAgent.class);
        LangGraphPlannerFacade plannerFacade = mock(LangGraphPlannerFacade.class);
        ChatModel branchChatModel = mock(ChatModel.class);

        // 构造 MastermindAgent 时保留原有工具依赖，但本测试只关注 PLAN_OR_RAG 分支
        MastermindAgent agent = new MastermindAgent(
                builder,
                mock(FlightTools.class),
                mock(WeatherTools.class),
                mock(PlacesTools.class),
                mock(KnowledgeTools.class),
                gatekeeperAgent,
                new ObjectMapper(),
                plannerFacade,
                branchChatModel);

        // Gatekeeper 返回复杂旅行规划意图，触发新版 graph workflow
        when(gatekeeperAgent.routeRequest("帮我规划法国10天"))
                .thenReturn("""
                        {"intent":"PLAN_OR_RAG","entities":{"locations":["法国"],"time":"国庆节","keywords":["10天"]}}
                        """);
        when(plannerFacade.plan(any())).thenReturn(GraphResult.success("graph answer", List.of()));

        String answer = agent.handleUserWorkflow("帮我规划法国10天");

        // 最终答案直接来自 Facade，证明 MastermindAgent 已经进入直线规划黑箱
        assertThat(answer).isEqualTo("graph answer");
        verify(plannerFacade).plan(any());
    }

    /**
     * 验证开放式规划请求即使被 Gatekeeper 误判为 TOOL_FLIGHT，也会被代码规则纠偏到 PLAN_OR_RAG。
     */
    @Test
    void planningLikeRequestOverridesMistakenToolFlightIntent() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));

        GatekeeperAgent gatekeeperAgent = mock(GatekeeperAgent.class);
        LangGraphPlannerFacade plannerFacade = mock(LangGraphPlannerFacade.class);
        ChatModel branchChatModel = mock(ChatModel.class);

        MastermindAgent agent = new MastermindAgent(
                builder,
                mock(FlightTools.class),
                mock(WeatherTools.class),
                mock(PlacesTools.class),
                mock(KnowledgeTools.class),
                gatekeeperAgent,
                new ObjectMapper(),
                plannerFacade,
                branchChatModel);

        when(gatekeeperAgent.routeRequest("我想下个月去欧洲玩，帮我安排"))
                .thenReturn("""
                        {"intent":"TOOL_FLIGHT","entities":{"locations":["欧洲"],"time":"下个月","keywords":["安排"]}}
                        """);
        when(plannerFacade.plan(any())).thenReturn(GraphResult.success("graph answer", List.of()));

        String answer = agent.handleUserWorkflow("我想下个月去欧洲玩，帮我安排");

        assertThat(answer).isEqualTo("graph answer");
        verify(plannerFacade).plan(any());
    }

    /**
     * 验证明示航班查询仍保留在 TOOL_FLIGHT 分支，并返回第一阶段占位文本。
     */
    @Test
    void explicitFlightRequestStaysInToolBranch() {
        ChatClient.Builder builder = mock(ChatClient.Builder.class);
        when(builder.build()).thenReturn(mock(ChatClient.class));

        GatekeeperAgent gatekeeperAgent = mock(GatekeeperAgent.class);
        LangGraphPlannerFacade plannerFacade = mock(LangGraphPlannerFacade.class);
        ChatModel branchChatModel = mock(ChatModel.class);

        MastermindAgent agent = new MastermindAgent(
                builder,
                mock(FlightTools.class),
                mock(WeatherTools.class),
                mock(PlacesTools.class),
                mock(KnowledgeTools.class),
                gatekeeperAgent,
                new ObjectMapper(),
                plannerFacade,
                branchChatModel);

        when(gatekeeperAgent.routeRequest("帮我查下个月去巴黎的机票"))
                .thenReturn("""
                        {"intent":"TOOL_FLIGHT","entities":{"locations":["巴黎"],"time":"下个月","keywords":["机票"]}}
                        """);

        String answer = agent.handleUserWorkflow("帮我查下个月去巴黎的机票");

        assertThat(answer).contains("机票/航班查询请求");
        assertThat(answer).contains("下一阶段接入");
        verify(plannerFacade, never()).plan(any());
    }
}
