package com.travel.agent.ai.agents;

import com.travel.agent.ai.graph.model.AdaptiveRagDecision;
import com.travel.agent.ai.graph.model.RagQueryType;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RagQueryClassifierAgent 的单元测试。
 *
 * <p>重点验证第 14 阶段 Adaptive RAG 的第一道分流规则，避免比较、教程和完整行程规划
 * 全部退化成同一种固定 similarity search。</p>
 */
class RagQueryClassifierAgentTest {

    private final RagQueryClassifierAgent classifier = new RagQueryClassifierAgent();

    @Test
    void classifiesComparativeQuestion() {
        TravelPlanState state = new TravelPlanState();
        state.setUserQuery("巴黎和尼斯哪个更适合亲子？");
        state.setDestinations(List.of("巴黎", "尼斯"));

        AdaptiveRagDecision decision = classifier.classify(state);

        assertThat(decision.getQueryType()).isEqualTo(RagQueryType.COMPARATIVE);
        assertThat(decision.getReason()).contains("比较");
    }

    @Test
    void classifiesInstructionalQuestion() {
        TravelPlanState state = new TravelPlanState();
        state.setUserQuery("怎么买卢浮宫门票，预约流程是什么？");

        AdaptiveRagDecision decision = classifier.classify(state);

        assertThat(decision.getQueryType()).isEqualTo(RagQueryType.INSTRUCTIONAL);
    }

    @Test
    void classifiesConfirmedMultiDestinationPlanAsMultiHop() {
        TravelRequirementSpec spec = new TravelRequirementSpec();
        spec.setDestinations(List.of("法国", "瑞士"));
        spec.setDurationDays(5);
        spec.setBudgetAmount(new BigDecimal("1500"));
        spec.setBudgetCurrency("EUR");

        TravelPlanState state = new TravelPlanState();
        state.setUserQuery("从都柏林出发，2026年7月1日去法国和瑞士玩5天，预算1500欧。");
        state.setRequirementSpec(spec);

        AdaptiveRagDecision decision = classifier.classify(state);

        assertThat(decision.getQueryType()).isEqualTo(RagQueryType.MULTI_HOP);
    }

    @Test
    void classifiesExploratoryQuestion() {
        TravelPlanState state = new TravelPlanState();
        state.setUserQuery("法国有哪些小众海边城市值得慢游？");

        AdaptiveRagDecision decision = classifier.classify(state);

        assertThat(decision.getQueryType()).isEqualTo(RagQueryType.EXPLORATORY);
    }
}
