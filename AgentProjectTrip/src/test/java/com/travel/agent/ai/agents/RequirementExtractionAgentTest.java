package com.travel.agent.ai.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * RequirementExtractionAgent 的单元测试。
 *
 * <p>重点验证第五阶段自然语言到结构化需求表的抽取、模型失败降级和规则补强能力。</p>
 */
class RequirementExtractionAgentTest {

    /**
     * 规则兜底应能识别常见旅行规划中的关键字段。
     */
    @Test
    void extractByRulesReadsCoreTravelRequirementFields() {
        TravelRequirementSpec spec = RequirementExtractionAgent.extractByRules(
                "s1",
                "国庆去法国和意大利玩10天，预算1200欧，不含国际机票，2个人，从上海出发，想避开人多");

        assertThat(spec.getDestinations()).containsExactly("法国", "意大利");
        assertThat(spec.getStartDateText()).isEqualTo("国庆");
        assertThat(spec.getDurationDays()).isEqualTo(10);
        assertThat(spec.getBudgetAmount()).isEqualByComparingTo("1200");
        assertThat(spec.getBudgetCurrency()).isEqualTo("EUR");
        assertThat(spec.getBudgetIncludesInternationalFlight()).isFalse();
        assertThat(spec.getTravelerCount()).isEqualTo(2);
        assertThat(spec.getDepartureCity()).isEqualTo("上海");
        assertThat(spec.getAvoidances()).contains("避开人多");
    }

    /**
     * 模型 JSON 如果漏掉确定性字段，Agent 应用规则抽取结果补齐，而不是把空字段交给前端。
     */
    @Test
    void extractFillsModelMissingFieldsWithRuleFallback() {
        RequirementExtractionAgent agent = new RequirementExtractionAgent(mock(ChatClient.class), new ObjectMapper()) {
            @Override
            protected String callModel(String message) {
                return """
                        {
                          "destinations": ["法国", "意大利"],
                          "preferences": ["小众"]
                        }
                        """;
            }
        };

        TravelRequirementSpec spec = agent.extract(
                "s1",
                "国庆去法国和意大利玩10天，预算1200欧，不含国际机票，2个人，从上海出发，想避开人多");

        assertThat(spec.getRequirementId()).startsWith("req-");
        assertThat(spec.getDurationDays()).isEqualTo(10);
        assertThat(spec.getBudgetAmount()).isEqualByComparingTo("1200");
        assertThat(spec.getBudgetCurrency()).isEqualTo("EUR");
        assertThat(spec.getBudgetIncludesInternationalFlight()).isFalse();
        assertThat(spec.getTravelerCount()).isEqualTo(2);
        assertThat(spec.getDepartureCity()).isEqualTo("上海");
    }

    /**
     * 模型调用失败时不应中断请求，应返回规则兜底需求表。
     */
    @Test
    void extractFallsBackToRulesWhenModelFails() {
        RequirementExtractionAgent agent = new RequirementExtractionAgent(mock(ChatClient.class), new ObjectMapper()) {
            @Override
            protected String callModel(String message) {
                throw new RuntimeException("model down");
            }
        };

        TravelRequirementSpec spec = agent.extract("s1", "我想下个月去欧洲玩，预算1000欧，一周左右");

        assertThat(spec.getDestinations()).containsExactly("欧洲");
        assertThat(spec.getStartDateText()).isEqualTo("下个月");
        assertThat(spec.getDurationDays()).isEqualTo(7);
        assertThat(spec.getBudgetAmount()).isEqualByComparingTo("1000");
        assertThat(spec.getBudgetCurrency()).isEqualTo("EUR");
    }
}
