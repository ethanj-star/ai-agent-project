package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.BranchDispatchDecision;
import com.travel.agent.ai.graph.model.BranchDispatchIssue;
import com.travel.agent.ai.graph.model.BranchTaskSuggestion;
import com.travel.agent.ai.graph.model.BranchTaskType;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BranchDispatchGuardNode 的单元测试。
 *
 * <p>测试重点是 Java Guard 对模型建议的白名单、参数和降级边界控制。</p>
 */
class BranchDispatchGuardNodeTest {

    private final BranchDispatchGuardNode guard = new BranchDispatchGuardNode(new BranchDispatchNode());

    /**
     * 模型建议合法的航班、酒店和知识任务时，Guard 应转换成可执行 BranchTask。
     */
    @Test
    void guardAcceptsValidModelSuggestions() {
        TravelPlanState state = completeState();
        BranchDispatchDecision decision = new BranchDispatchDecision();
        decision.setTasks(List.of(
                new BranchTaskSuggestion("FLIGHT", "HIGH", "需要真实航班参考。"),
                new BranchTaskSuggestion("HOTEL", "HIGH", "需要住宿价格参考。"),
                new BranchTaskSuggestion("KNOWLEDGE", "MEDIUM", "需要目的地攻略背景。")));

        TravelPlanState result = guard.guard(state, decision);

        assertThat(result.getBranchTasks())
                .extracting("type")
                .containsExactly(BranchTaskType.FLIGHT, BranchTaskType.HOTEL, BranchTaskType.KNOWLEDGE);
        assertThat(result.getBranchTasks().get(0).getDispatchReason()).isEqualTo("需要真实航班参考。");
        assertThat(result.getBranchTasks().get(0).getDepartureCity()).isEqualTo("上海");
        assertThat(result.getBranchDispatchIssues())
                .extracting(BranchDispatchIssue::getAction)
                .contains("ACCEPTED");
    }

    /**
     * 模型建议未知工具时，Guard 应拒绝该任务，不能自动创建新工具或绕过白名单。
     */
    @Test
    void guardRejectsUnknownToolType() {
        TravelPlanState state = completeState();
        BranchDispatchDecision decision = new BranchDispatchDecision();
        decision.setTasks(List.of(new BranchTaskSuggestion("VISA", "HIGH", "模型想查签证。")));

        TravelPlanState result = guard.guard(state, decision);

        assertThat(result.getBranchTasks()).isEmpty();
        assertThat(result.getBranchDispatchIssues())
                .anySatisfy(issue -> {
                    assertThat(issue.getAction()).isEqualTo("REJECTED");
                    assertThat(issue.getType()).isEqualTo("VISA");
                    assertThat(issue.getReason()).contains("不存在或未接入");
                });
    }

    /**
     * 模型把未来旅行误派给实时天气工具时，Guard 应拒绝 WEATHER。
     */
    @Test
    void guardRejectsWeatherForFutureTravel() {
        TravelPlanState state = completeState();
        state.setTravelTime("2026年10月1日");
        state.setUserQuery("2026年10月去法国和意大利玩10天");
        BranchDispatchDecision decision = new BranchDispatchDecision();
        decision.setTasks(List.of(new BranchTaskSuggestion("WEATHER", "HIGH", "模型误以为要查未来天气。")));

        TravelPlanState result = guard.guard(state, decision);

        assertThat(result.getBranchTasks()).isEmpty();
        assertThat(result.getBranchDispatchIssues())
                .anySatisfy(issue -> {
                    assertThat(issue.getType()).isEqualTo("WEATHER");
                    assertThat(issue.getReason()).contains("只支持实时天气");
                });
    }

    /**
     * 航班分支缺少出发地或明确日期时，Guard 应拒绝，避免真实航班工具拿到不可查参数。
     */
    @Test
    void guardRejectsFlightWhenRequiredFieldsMissing() {
        TravelPlanState state = completeState();
        state.getRequirementSpec().setStartDate(null);
        BranchDispatchDecision decision = new BranchDispatchDecision();
        decision.setTasks(List.of(new BranchTaskSuggestion("FLIGHT", "HIGH", "需要查航班。")));

        TravelPlanState result = guard.guard(state, decision);

        assertThat(result.getBranchTasks()).isEmpty();
        assertThat(result.getBranchDispatchIssues())
                .anySatisfy(issue -> {
                    assertThat(issue.getType()).isEqualTo("FLIGHT");
                    assertThat(issue.getReason()).contains("startDate");
                });
    }

    /**
     * 模型派发失败或空输出时，Guard 应回退旧 BranchDispatchNode，保证基础规则派发仍可用。
     */
    @Test
    void guardFallsBackToRuleBasedDispatchWhenDecisionRequiresFallback() {
        TravelPlanState state = completeState();
        state.setUserQuery("帮我安排法国和意大利景点，想住经济酒店");
        BranchDispatchDecision decision = BranchDispatchDecision.fallback("模型 JSON 解析失败。");

        TravelPlanState result = guard.guard(state, decision);

        assertThat(result.getBranchTasks())
                .extracting("type")
                .contains(BranchTaskType.KNOWLEDGE, BranchTaskType.PLACES, BranchTaskType.HOTEL);
        assertThat(result.getBranchDispatchIssues())
                .anySatisfy(issue -> assertThat(issue.getAction()).isEqualTo("FALLBACK"));
    }

    private static TravelPlanState completeState() {
        TravelRequirementSpec spec = new TravelRequirementSpec();
        spec.setDestinations(List.of("法国", "意大利"));
        spec.setDepartureCity("上海");
        spec.setStartDate(LocalDate.of(2026, 10, 1));
        spec.setDurationDays(10);
        spec.setBudgetAmount(BigDecimal.valueOf(2000));
        spec.setBudgetCurrency("EUR");
        spec.setAccommodationPreference("经济酒店");
        spec.setBudgetIncludesInternationalFlight(true);

        TravelPlanState state = new TravelPlanState();
        state.setUserQuery("从上海出发，2026年10月1日去法国和意大利玩10天，预算2000欧，想住经济酒店");
        state.setRequirementSpec(spec);
        state.setDestinations(spec.getDestinations());
        state.setTravelTime("2026-10-01");
        state.setDurationDays(10);
        state.setKeywords(List.of("预算2000欧", "经济酒店"));
        return state;
    }
}
