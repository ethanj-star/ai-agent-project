package com.travel.agent.ai.graph.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.ai.graph.model.BranchResult;
import com.travel.agent.ai.graph.model.BranchTask;
import com.travel.agent.ai.graph.model.BranchTaskType;
import com.travel.agent.ai.graph.model.PlannerDraft;
import com.travel.agent.ai.graph.model.TravelPlanState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PlanDraftNode 的单元测试。
 *
 * <p>测试范围刻意避开真实模型调用，只验证 prompt 注入、JSON 解析和非 JSON 降级策略。</p>
 */
class PlanDraftNodeTest {

    /** 使用测试构造器注入空 ChatClient；本类不会调用 callModel。 */
    private final PlanDraftNode node = new PlanDraftNode((ChatClient) null, new ObjectMapper());

    /**
     * Prompt 必须包含用户原文、结构化实体、RAG 上下文和当前日期提示。
     */
    @Test
    void buildSystemPromptContainsRagContextAndCurrentState() {
        TravelPlanState state = new TravelPlanState();
        state.setUserQuery("帮我规划法国10天");
        state.setDestinations(List.of("法国"));
        state.setTravelTime("国庆节");
        state.setDurationDays(10);
        state.setDurationText("10天");
        state.setKeywords(List.of("防坑"));
        state.setRagContext("卢浮宫需要预约");
        BranchTask weatherTask = new BranchTask("weather-1", BranchTaskType.WEATHER, "巴黎天气",
                List.of("巴黎"), "国庆节", List.of("防坑"));
        state.setBranchResults(List.of(
                BranchResult.success(weatherTask, "天气参考：巴黎 18°C，小雨。", "WeatherDTO[...]")));

        String prompt = node.buildSystemPrompt(state);

        assertThat(prompt).contains("帮我规划法国10天");
        assertThat(prompt).contains("法国");
        assertThat(prompt).contains("国庆节");
        assertThat(prompt).contains("行程时长：10天");
        assertThat(prompt).contains("卢浮宫需要预约");
        assertThat(prompt).contains("分支 Agent 结果");
        assertThat(prompt).contains("天气参考：巴黎");
        assertThat(prompt).contains("当前系统日期");
    }

    /**
     * 模型返回合法 JSON Object 时，应解析为 PlannerDraft。
     */
    @Test
    void parseOrFallbackParsesJsonObject() {
        PlannerDraft draft = node.parseOrFallback("""
                {
                  "title": "法国10天",
                  "summary": "轻松路线",
                  "itineraryMarkdown": "Day 1 Paris",
                  "budgetNotes": "预算需要复核",
                  "riskNotes": "注意预约",
                  "assumptions": ["假设从都柏林出发"]
                }
                """);

        assertThat(draft.getTitle()).isEqualTo("法国10天");
        assertThat(draft.getSummary()).isEqualTo("轻松路线");
        assertThat(draft.getAssumptions()).containsExactly("假设从都柏林出发");
    }

    /**
     * 模型返回普通文本时，应降级为可展示的 Markdown 草案，而不是抛异常。
     */
    @Test
    void parseOrFallbackFallsBackForNonJsonText() {
        PlannerDraft draft = node.parseOrFallback("这是一段普通 Markdown 规划草案");

        assertThat(draft.getTitle()).isEqualTo("欧洲旅行规划草案");
        assertThat(draft.getItineraryMarkdown()).contains("普通 Markdown");
        assertThat(draft.getAssumptions()).contains("模型未返回合法 JSON，系统已按文本草案降级处理。");
    }
}
