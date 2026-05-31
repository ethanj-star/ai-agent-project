package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.dto.GatekeeperResponse;
import com.travel.agent.ai.graph.model.ClarificationQuestion;
import com.travel.agent.ai.graph.model.GraphInputRequest;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.WorkflowStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * MergeClarificationNode 的单元测试。
 *
 * <p>重点验证用户补充信息能够合并回上一轮 pending 任务。</p>
 */
class MergeClarificationNodeTest {

    private final MergeClarificationNode node = new MergeClarificationNode();

    /**
     * 用户回答追问后，应合并原始需求和补充信息，并用新实体刷新目的地。
     */
    @Test
    void mergeCombinesOriginalQueryAndCurrentAnswer() {
        TravelPlanState pending = new TravelPlanState();
        pending.setSessionId("s1");
        pending.setUserQuery("我想下个月去欧洲玩，帮我安排");
        pending.setDestinations(List.of("欧洲"));
        pending.setWorkflowStatus(WorkflowStatus.NEEDS_CLARIFICATION);
        pending.setPendingQuestions(List.of(
                new ClarificationQuestion("destination_scope", "destinations", "你想去哪里？", true)));

        GatekeeperResponse route = new GatekeeperResponse();
        GatekeeperResponse.Entities entities = new GatekeeperResponse.Entities();
        entities.setLocations(List.of("法国", "意大利"));
        entities.setTime("下个月");
        entities.setKeywords(List.of("10天", "预算1200欧", "避开人多"));
        route.setEntities(entities);

        GraphInputRequest request = new GraphInputRequest(
                "法国和意大利，10天，预算1200欧，不含国际机票，想避开人多的地方",
                route,
                "s1");

        TravelPlanState result = node.merge(pending, request);

        assertThat(result.getWorkflowStatus()).isEqualTo(WorkflowStatus.PLANNING);
        assertThat(result.getUserQuery())
                .contains("我想下个月去欧洲玩")
                .contains("用户补充信息：法国和意大利");
        assertThat(result.getDestinations()).containsExactly("法国", "意大利");
        assertThat(result.getTravelTime()).isEqualTo("下个月");
        assertThat(result.getDurationDays()).isEqualTo(10);
        assertThat(result.getDurationText()).isEqualTo("10天");
        assertThat(result.getKeywords()).containsExactly("预算1200欧", "避开人多");
        assertThat(result.getPendingQuestions()).isEmpty();
        assertThat(result.getClarificationAnswers()).containsExactly(request.getUserQuery());
    }
}
