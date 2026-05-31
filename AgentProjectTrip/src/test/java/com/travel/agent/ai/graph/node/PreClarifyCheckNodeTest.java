package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.ValidationIssue;
import com.travel.agent.ai.graph.model.WorkflowStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PreClarifyCheckNode 的单元测试。
 *
 * <p>重点验证 RAG 和 Planner 之前的低成本澄清判断规则。</p>
 */
class PreClarifyCheckNodeTest {

    private final PreClarifyCheckNode node = new PreClarifyCheckNode();

    /**
     * 目的地只有“欧洲”时，应直接标记为需要澄清。
     */
    @Test
    void checkMarksBroadDestinationAsNeedsClarification() {
        TravelPlanState state = new TravelPlanState();
        state.setDestinations(List.of("欧洲"));

        TravelPlanState result = node.check(state);

        assertThat(result.getWorkflowStatus()).isEqualTo(WorkflowStatus.NEEDS_CLARIFICATION);
        assertThat(result.getValidationIssues())
                .extracting(ValidationIssue::getCode)
                .containsExactly("BROAD_DESTINATION");
    }

    /**
     * 目的地已经具体到国家时，应允许继续进入 RAG 和 Planner。
     */
    @Test
    void checkAllowsSpecificDestinations() {
        TravelPlanState state = new TravelPlanState();
        state.setDestinations(List.of("法国", "意大利"));

        TravelPlanState result = node.check(state);

        assertThat(result.getWorkflowStatus()).isEqualTo(WorkflowStatus.PLANNING);
        assertThat(result.getValidationIssues()).isEmpty();
    }
}
