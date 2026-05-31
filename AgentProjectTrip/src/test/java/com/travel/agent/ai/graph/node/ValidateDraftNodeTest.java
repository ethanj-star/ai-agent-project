package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.PlannerDraft;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.ValidationIssue;
import com.travel.agent.ai.graph.model.WorkflowStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ValidateDraftNode 的单元测试。
 *
 * <p>重点验证第一阶段 Java 规则校验：目的地、日期、草案内容和预算回应。</p>
 */
class ValidateDraftNodeTest {

    private final ValidateDraftNode node = new ValidateDraftNode();

    /**
     * 缺少目的地、日期和草案时，应输出对应结构化 ValidationIssue。
     */
    @Test
    void validateFindsMissingDestinationDateAndEmptyDraft() {
        TravelPlanState state = new TravelPlanState();
        state.setTravelTime("未指定");

        TravelPlanState result = node.validate(state);

        assertThat(result.getValidationIssues())
                .extracting(ValidationIssue::getCode)
                .contains("MISSING_DESTINATION", "MISSING_DATE", "EMPTY_DRAFT");
    }

    /**
     * 用户提到预算但草案没有预算说明时，应输出 BUDGET_NOT_ADDRESSED。
     */
    @Test
    void validateFindsBudgetNotAddressed() {
        TravelPlanState state = new TravelPlanState();
        state.setUserQuery("预算1200欧，法国10天");
        state.setDestinations(List.of("法国"));
        state.setTravelTime("国庆节");
        state.setRagContext("检索到的参考攻略");

        PlannerDraft draft = new PlannerDraft();
        draft.setItineraryMarkdown("Day 1 Paris. Day 2 Louvre. Day 3 Nice. Day 4 Lyon. Day 5 Rome. "
                + "Day 6 Florence. Day 7 Venice. Day 8 Milan. Day 9 Zurich. Day 10 Paris.");
        state.setDraft(draft);

        TravelPlanState result = node.validate(state);

        assertThat(result.getValidationIssues())
                .extracting(ValidationIssue::getCode)
                .contains("BUDGET_NOT_ADDRESSED");
    }

    /**
     * 只有“欧洲”这类宽泛目的地时，应提示用户补充具体国家或城市。
     */
    @Test
    void validateFindsBroadDestination() {
        TravelPlanState state = new TravelPlanState();
        state.setUserQuery("我想下个月去欧洲玩，帮我安排");
        state.setDestinations(List.of("欧洲"));
        state.setTravelTime("下个月");
        state.setRagContext("检索到的参考攻略");

        PlannerDraft draft = new PlannerDraft();
        draft.setBudgetNotes("预算需要后续确认。");
        draft.setItineraryMarkdown("Day 1 Paris. Day 2 Lyon. Day 3 Nice. Day 4 Rome. Day 5 Florence. "
                + "Day 6 Venice. Day 7 Milan. Day 8 Zurich. Day 9 Lucerne. Day 10 Paris.");
        state.setDraft(draft);

        TravelPlanState result = node.validate(state);

        assertThat(result.getValidationIssues())
                .extracting(ValidationIssue::getCode)
                .contains("BROAD_DESTINATION");
        assertThat(result.getWorkflowStatus()).isEqualTo(WorkflowStatus.NEEDS_CLARIFICATION);
    }
}
