package com.travel.agent.ai.graph.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.ai.graph.model.PlannerDraft;
import com.travel.agent.ai.graph.model.RiskAssessment;
import com.travel.agent.ai.graph.model.RiskIssue;
import com.travel.agent.ai.graph.model.RiskIssueType;
import com.travel.agent.ai.graph.model.TravelPlanState;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * TripRiskReasoningNode 的单元测试。
 *
 * <p>测试重点是确定性风险规则和模型 JSON 解析，不触发真实大模型调用。</p>
 */
class TripRiskReasoningNodeTest {

    private final TripRiskReasoningNode node = new TripRiskReasoningNode((ChatClient) null, new ObjectMapper());

    /**
     * 用户要求避开人多，但草案堆叠热门景点时，应输出 CROWD_CONFLICT。
     */
    @Test
    void assessFindsCrowdConflict() {
        TravelPlanState state = new TravelPlanState();
        state.setUserQuery("法国和意大利10天，想避开人多，少去网红景点");
        PlannerDraft draft = new PlannerDraft();
        draft.setItineraryMarkdown("第1天 卢浮宫。第2天 埃菲尔铁塔。第3天 巴黎圣母院。"
                + "第4天 凡尔赛。第5天 罗马斗兽场。");
        state.setDraft(draft);

        RiskAssessment result = node.assessByRules(state);

        assertThat(result.isNeedsRevision()).isTrue();
        assertThat(result.getIssues())
                .extracting(RiskIssue::getType)
                .contains(RiskIssueType.CROWD_CONFLICT);
    }

    /**
     * 用户指定 10 天但草案只有 8 天时，应输出 DURATION_MISMATCH。
     */
    @Test
    void assessFindsDurationMismatch() {
        TravelPlanState state = new TravelPlanState();
        state.setDurationDays(10);
        state.setDurationText("10天");
        PlannerDraft draft = new PlannerDraft();
        draft.setItineraryMarkdown("第1天 巴黎\n第2天 里昂\n第3天 尼斯\n第4天 都灵\n"
                + "第5天 博洛尼亚\n第6天 佛罗伦萨\n第7天 罗马\n第8天 罗马返程");
        state.setDraft(draft);

        RiskAssessment result = node.assessByRules(state);

        assertThat(result.isNeedsRevision()).isTrue();
        assertThat(result.getIssues())
                .extracting(RiskIssue::getType)
                .contains(RiskIssueType.DURATION_MISMATCH);
    }

    /**
     * 模型返回合法 JSON 时，应解析为 RiskAssessment。
     */
    @Test
    void parseAssessmentParsesJson() throws Exception {
        RiskAssessment result = node.parseAssessment("""
                {
                  "needsRevision": true,
                  "needsClarification": false,
                  "issues": [
                    {
                      "type": "CROWD_CONFLICT",
                      "severity": "HIGH",
                      "code": "CROWD_CONFLICT",
                      "message": "热门景点过多",
                      "evidence": "草案包含多个高人流点",
                      "suggestedAction": "减少热门景点",
                      "autoRevisable": true,
                      "requiresClarification": false
                    }
                  ],
                  "revisionInstruction": "减少热门景点"
                }
                """);

        assertThat(result.isNeedsRevision()).isTrue();
        assertThat(result.getIssues()).hasSize(1);
        assertThat(result.getIssues().get(0).getType()).isEqualTo(RiskIssueType.CROWD_CONFLICT);
    }

    /**
     * 模型不应把可自动修正的避峰冲突升级成用户澄清问题。
     */
    @Test
    void assessSanitizesClarificationFlagForAutoRevisableRisk() {
        TripRiskReasoningNode modelNode = new TripRiskReasoningNode(mock(ChatClient.class), new ObjectMapper()) {
            @Override
            protected String callModel(String systemPrompt, String userQuery) {
                return """
                        {
                          "needsRevision": true,
                          "needsClarification": true,
                          "issues": [
                            {
                              "type": "CROWD_CONFLICT",
                              "severity": "HIGH",
                              "code": "CROWD_CONFLICT",
                              "message": "热门景点过多",
                              "evidence": "草案包含多个高人流点",
                              "suggestedAction": "减少热门景点",
                              "autoRevisable": true,
                              "requiresClarification": true
                            }
                          ],
                          "revisionInstruction": "减少热门景点"
                        }
                        """;
            }
        };
        TravelPlanState state = new TravelPlanState();
        PlannerDraft draft = new PlannerDraft();
        draft.setItineraryMarkdown("第1天 巴黎小众街区");
        state.setDraft(draft);

        TravelPlanState result = modelNode.assess(state);

        assertThat(result.getRiskAssessment().isNeedsRevision()).isTrue();
        assertThat(result.getRiskAssessment().isNeedsClarification()).isFalse();
        assertThat(result.getRiskAssessment().getIssues().get(0).isRequiresClarification()).isFalse();
    }

    /**
     * 目的地被草案遗漏时，应输出 DESTINATION_MISMATCH。
     */
    @Test
    void assessFindsDestinationMismatch() {
        TravelPlanState state = new TravelPlanState();
        state.setDestinations(List.of("法国", "意大利"));
        PlannerDraft draft = new PlannerDraft();
        draft.setItineraryMarkdown("第1天 巴黎。第2天 里昂。第3天 尼斯。");
        state.setDraft(draft);

        RiskAssessment result = node.assessByRules(state);

        assertThat(result.getIssues())
                .extracting(RiskIssue::getType)
                .contains(RiskIssueType.DESTINATION_MISMATCH);
    }
}
