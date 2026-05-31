package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.agents.BranchAgentFacade;
import com.travel.agent.ai.graph.model.BranchResult;
import com.travel.agent.ai.graph.model.BranchTask;
import com.travel.agent.ai.graph.model.BranchTaskType;
import com.travel.agent.ai.graph.model.TravelPlanState;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BranchExecuteNode 的单元测试。
 *
 * <p>测试重点是 Graph 节点是否正确把任务交给 BranchAgentFacade，并把结果写回状态。</p>
 */
class BranchExecuteNodeTest {

    /**
     * 有分支任务时，应逐个调用 BranchAgentFacade，并保存所有结果。
     */
    @Test
    void executeRunsEveryBranchTaskAndStoresResults() {
        BranchAgentFacade facade = mock(BranchAgentFacade.class);
        BranchExecuteNode node = new BranchExecuteNode(facade);
        BranchTask weatherTask = new BranchTask("weather-1", BranchTaskType.WEATHER, "巴黎天气",
                List.of("巴黎"), "下个月", List.of());
        BranchTask placesTask = new BranchTask("places-1", BranchTaskType.PLACES, "巴黎景点",
                List.of("巴黎"), "下个月", List.of());

        when(facade.execute(weatherTask)).thenReturn(BranchResult.success(weatherTask, "天气参考", "raw-weather"));
        when(facade.execute(placesTask)).thenReturn(BranchResult.success(placesTask, "景点参考", "raw-places"));

        TravelPlanState state = new TravelPlanState();
        state.setBranchTasks(List.of(weatherTask, placesTask));

        TravelPlanState result = node.execute(state);

        assertThat(result.getBranchResults()).hasSize(2);
        assertThat(result.getBranchResults())
                .extracting(BranchResult::getSummary)
                .containsExactly("天气参考", "景点参考");
        verify(facade).execute(weatherTask);
        verify(facade).execute(placesTask);
    }

    /**
     * 没有分支任务时，应写入空结果列表并直接返回。
     */
    @Test
    void executeReturnsEmptyResultsWhenNoTasks() {
        BranchAgentFacade facade = mock(BranchAgentFacade.class);
        BranchExecuteNode node = new BranchExecuteNode(facade);
        TravelPlanState state = new TravelPlanState();

        TravelPlanState result = node.execute(state);

        assertThat(result.getBranchResults()).isEmpty();
    }
}
