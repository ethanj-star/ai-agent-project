package com.travel.agent.ai.graph.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.ai.graph.model.PlannerDraft;
import com.travel.agent.ai.graph.model.RiskAssessment;
import com.travel.agent.ai.graph.model.RiskIssue;
import com.travel.agent.ai.graph.model.RiskIssueType;
import com.travel.agent.ai.graph.model.RiskSeverity;
import com.travel.agent.ai.graph.model.TravelPlanState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PlanRevisionNode 的单元测试。
 *
 * <p>测试范围刻意避开真实模型调用，只验证 revision prompt、JSON 解析和状态更新。</p>
 */
class PlanRevisionNodeTest {

    /**
     * 存在可自动修正风险时，应调用模型重写草案并增加 revisionCount。
     */
    @Test
    void reviseUpdatesDraftAndIncrementsRevisionCount() {
        PlanRevisionNode node = new PlanRevisionNode((ChatClient) null, new ObjectMapper()) {
            @Override
            protected String callModel(String systemPrompt, String userQuery) {
                assertThat(systemPrompt).contains("CROWD_CONFLICT").contains("减少热门景点");
                return """
                        {
                          "title": "修正版小众路线",
                          "summary": "减少热门景点，改为小众慢游。",
                          "itineraryMarkdown": "第1天 小众街区",
                          "budgetNotes": "预算不含国际机票。",
                          "riskNotes": "出发前复核交通。",
                          "assumptions": []
                        }
                        """;
            }
        };

        TravelPlanState state = buildStateWithRisk();

        TravelPlanState result = node.revise(state);

        assertThat(result.getRevisionCount()).isEqualTo(1);
        assertThat(result.getDraft().getTitle()).isEqualTo("修正版小众路线");
        assertThat(result.getDraft().getAssumptions()).contains("系统已根据输出前风险审查结果自动修正过本方案。");
    }

    /**
     * 修正 Prompt 必须包含用户硬约束和风险问题。
     */
    @Test
    void buildRevisionPromptContainsConstraintsAndRiskIssues() {
        PlanRevisionNode node = new PlanRevisionNode((ChatClient) null, new ObjectMapper());
        TravelPlanState state = buildStateWithRisk();

        String prompt = node.buildRevisionPrompt(state);

        assertThat(prompt).contains("不含国际机票");
        assertThat(prompt).contains("行程时长：10天");
        assertThat(prompt).contains("CROWD_CONFLICT");
        assertThat(prompt).contains("只输出合法 JSON Object");
    }

    /**
     * 模型返回非 JSON 时，应保留原草案并写入假设说明。
     */
    @Test
    void parseOrFallbackKeepsOldDraftForNonJsonResponse() {
        PlanRevisionNode node = new PlanRevisionNode((ChatClient) null, new ObjectMapper());
        PlannerDraft oldDraft = new PlannerDraft();
        oldDraft.setTitle("旧草案");
        oldDraft.setAssumptions(List.of());

        PlannerDraft result = node.parseOrFallback("这不是 JSON", oldDraft);

        assertThat(result).isSameAs(oldDraft);
        assertThat(result.getAssumptions()).contains("自动修正模型未返回合法 JSON，系统保留原草案。");
    }

    private static TravelPlanState buildStateWithRisk() {
        PlannerDraft draft = new PlannerDraft();
        draft.setTitle("热门路线");
        draft.setSummary("热门景点较多。");
        draft.setItineraryMarkdown("第1天 卢浮宫，第2天 埃菲尔铁塔。");
        draft.setBudgetNotes("预算不含国际机票。");
        draft.setRiskNotes("注意预约。");
        draft.setAssumptions(List.of());

        RiskIssue issue = RiskIssue.autoRevisable(
                RiskIssueType.CROWD_CONFLICT,
                RiskSeverity.HIGH,
                "CROWD_CONFLICT",
                "热门景点过多",
                "草案包含多个高人流点",
                "减少热门景点");
        RiskAssessment assessment = new RiskAssessment(true, false, List.of(issue), "减少热门景点");

        TravelPlanState state = new TravelPlanState();
        state.setUserQuery("国庆去法国和意大利10天，预算1200欧，不含国际机票，避开人多");
        state.setDestinations(List.of("法国", "意大利"));
        state.setTravelTime("国庆");
        state.setDurationDays(10);
        state.setDurationText("10天");
        state.setKeywords(List.of("预算1200欧", "不含国际机票", "避开人多"));
        state.setDraft(draft);
        state.setRiskAssessment(assessment);
        return state;
    }
}
