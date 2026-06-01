package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.dto.GatekeeperResponse;
import com.travel.agent.ai.graph.model.GraphInputRequest;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InitStateNode 的单元测试。
 *
 * <p>重点验证 GatekeeperResponse 到 TravelPlanState 的字段搬运，以及 entities 缺失时的 null-safe 行为。</p>
 */
class InitStateNodeTest {

    private final InitStateNode node = new InitStateNode();

    /**
     * Gatekeeper 输出完整实体时，节点应把 locations、time、duration、keywords 正确写入状态。
     */
    @Test
    void initCopiesGatekeeperEntitiesIntoState() {
        GatekeeperResponse route = new GatekeeperResponse();
        GatekeeperResponse.Entities entities = new GatekeeperResponse.Entities();
        entities.setLocations(List.of("法国", "意大利"));
        entities.setTime("国庆节");
        entities.setKeywords(List.of("10天", "避开人多"));
        route.setEntities(entities);

        TravelPlanState state = node.init(new GraphInputRequest("帮我规划法国意大利", route));

        assertThat(state.getUserQuery()).isEqualTo("帮我规划法国意大利");
        assertThat(state.getRoute()).isSameAs(route);
        assertThat(state.getDestinations()).containsExactly("法国", "意大利");
        assertThat(state.getTravelTime()).isEqualTo("国庆节");
        assertThat(state.getDurationDays()).isEqualTo(10);
        assertThat(state.getDurationText()).isEqualTo("10天");
        assertThat(state.getKeywords()).containsExactly("避开人多");
        assertThat(state.isSuccess()).isFalse();
    }

    /**
     * Gatekeeper 把“10天”误放进 time 时，节点应把它归入 duration，并保持 travelTime 未指定。
     */
    @Test
    void initTreatsDurationLikeTimeAsDuration() {
        GatekeeperResponse route = new GatekeeperResponse();
        GatekeeperResponse.Entities entities = new GatekeeperResponse.Entities();
        entities.setLocations(List.of("法国"));
        entities.setTime("10天");
        entities.setKeywords(List.of("预算1200欧"));
        route.setEntities(entities);

        TravelPlanState state = node.init(new GraphInputRequest("法国玩10天", route));

        assertThat(state.getTravelTime()).isEqualTo("未指定");
        assertThat(state.getDurationDays()).isEqualTo(10);
        assertThat(state.getDurationText()).isEqualTo("10天");
    }

    /**
     * Gatekeeper 没有输出 entities 时，节点应生成空列表和“未指定”时间，避免后续节点 NPE。
     */
    @Test
    void initIsNullSafeWhenEntitiesAreMissing() {
        TravelPlanState state = node.init(new GraphInputRequest("随便安排", new GatekeeperResponse()));

        assertThat(state.getDestinations()).isEmpty();
        assertThat(state.getKeywords()).isEmpty();
        assertThat(state.getTravelTime()).isEqualTo("未指定");
    }

    /**
     * 第五阶段传入已确认需求表时，节点应优先用 TravelRequirementSpec 初始化状态。
     */
    @Test
    void initUsesRequirementSpecWhenPresent() {
        TravelRequirementSpec spec = new TravelRequirementSpec();
        spec.setDestinations(List.of("法国", "意大利"));
        spec.setStartDateText("国庆");
        spec.setDurationDays(10);
        spec.setBudgetAmount(BigDecimal.valueOf(1200));
        spec.setBudgetCurrency("EUR");
        spec.setBudgetIncludesInternationalFlight(false);
        spec.setTravelerCount(2);
        spec.setDepartureCity("上海");
        spec.setAvoidances(List.of("避开人多"));

        GraphInputRequest request = new GraphInputRequest("结构化需求生成", new GatekeeperResponse(), "s1");
        request.setRequirementSpec(spec);

        TravelPlanState state = node.init(request);

        assertThat(state.getRequirementSpec()).isSameAs(spec);
        assertThat(state.getDestinations()).containsExactly("法国", "意大利");
        assertThat(state.getTravelTime()).isEqualTo("国庆");
        assertThat(state.getDurationDays()).isEqualTo(10);
        assertThat(state.getDurationText()).isEqualTo("10天");
        assertThat(state.getKeywords()).contains("预算1200EUR", "不含国际机票", "2人", "出发地上海", "避开人多");
    }
}
