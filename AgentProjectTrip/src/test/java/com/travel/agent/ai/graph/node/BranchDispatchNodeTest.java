package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.BranchTaskType;
import com.travel.agent.ai.graph.model.TravelPlanState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * BranchDispatchNode 的单元测试。
 *
 * <p>只验证 Java 规则派发，不触发模型或外部工具。</p>
 */
class BranchDispatchNodeTest {

    private final BranchDispatchNode node = new BranchDispatchNode();

    /**
     * 用户明确需要当前天气时，应派发知识、天气和景点分支。
     */
    @Test
    void dispatchCreatesKnowledgeWeatherAndPlacesTasks() {
        TravelPlanState state = new TravelPlanState();
        state.setUserQuery("今天去巴黎玩，帮我安排景点并看实时天气");
        state.setDestinations(List.of("法国", "意大利"));
        state.setTravelTime("今天");
        state.setKeywords(List.of("行程", "避开人多"));

        TravelPlanState result = node.dispatch(state);

        assertThat(result.getBranchTasks())
                .extracting("type")
                .containsExactly(BranchTaskType.KNOWLEDGE, BranchTaskType.WEATHER, BranchTaskType.PLACES);
    }

    /**
     * 未来旅行时间不应触发实时天气分支，避免把当前天气误用于国庆/下个月行程。
     */
    @Test
    void dispatchSkipsWeatherTaskForFutureTravelTime() {
        TravelPlanState state = new TravelPlanState();
        state.setUserQuery("国庆去法国和意大利玩10天，预算1200欧，不含国际机票，想避开人多");
        state.setDestinations(List.of("法国", "意大利"));
        state.setTravelTime("国庆");
        state.setKeywords(List.of("预算1200欧", "避开人多"));

        TravelPlanState result = node.dispatch(state);

        assertThat(result.getBranchTasks())
                .extracting("type")
                .containsExactly(BranchTaskType.KNOWLEDGE, BranchTaskType.PLACES);
    }

    /**
     * 用户明确提到机票或航班时，应额外派发航班分支任务。
     */
    @Test
    void dispatchCreatesFlightTaskForExplicitFlightNeed() {
        TravelPlanState state = new TravelPlanState();
        state.setUserQuery("帮我查巴黎机票并安排景点");
        state.setDestinations(List.of("巴黎"));
        state.setTravelTime("下个月");

        TravelPlanState result = node.dispatch(state);

        assertThat(result.getBranchTasks())
                .extracting("type")
                .contains(BranchTaskType.FLIGHT);
    }

    /**
     * “不含国际机票”是在说明预算口径，不应误触发航班分支。
     */
    @Test
    void dispatchDoesNotCreateFlightTaskWhenTicketIsBudgetExclusion() {
        TravelPlanState state = new TravelPlanState();
        state.setUserQuery("法国和意大利，10天，预算1200欧，不含国际机票，想避开人多的地方");
        state.setDestinations(List.of("法国", "意大利"));
        state.setKeywords(List.of("10天", "预算1200欧", "不含国际机票", "避开人多"));

        TravelPlanState result = node.dispatch(state);

        assertThat(result.getBranchTasks())
                .extracting("type")
                .doesNotContain(BranchTaskType.FLIGHT)
                .contains(BranchTaskType.KNOWLEDGE, BranchTaskType.PLACES);
    }

    /**
     * 缺少目的地时不应派发依赖目的地的分支，避免工具拿到空查询。
     */
    @Test
    void dispatchSkipsDestinationBasedTasksWhenDestinationMissing() {
        TravelPlanState state = new TravelPlanState();
        state.setUserQuery("帮我随便安排一个旅行");

        TravelPlanState result = node.dispatch(state);

        assertThat(result.getBranchTasks()).isEmpty();
    }
}
