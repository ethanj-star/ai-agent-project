package com.travel.agent.ai.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.ai.graph.model.PlanModificationDecision;
import com.travel.agent.ai.graph.model.PlanModificationIntent;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * PlanModificationAgent 的单元测试。
 *
 * <p>重点验证第六阶段自然语言修改意图识别和模型失败后的规则兜底。</p>
 */
class PlanModificationAgentTest {

    /**
     * 针对某一天节奏的反馈应识别为局部修改。
     */
    @Test
    void decideByRulesDetectsLocalRevision() {
        PlanModificationDecision decision =
                PlanModificationAgent.decideByRules("第三天太赶了，少安排一个城市，多留一点自由时间");

        assertThat(decision.getIntent()).isEqualTo(PlanModificationIntent.LOCAL_REVISION);
        assertThat(decision.getTargetDay()).isEqualTo("第三天");
        assertThat(decision.getInstructionSummary()).contains("第三天太赶");
    }

    /**
     * 预算和住宿偏好变化应识别为核心需求变更。
     */
    @Test
    void decideByRulesDetectsRequirementChange() {
        PlanModificationDecision decision =
                PlanModificationAgent.decideByRules("预算改成900欧，不想住青旅，改成经济型酒店");

        assertThat(decision.getIntent()).isEqualTo(PlanModificationIntent.REQUIREMENT_CHANGE);
        assertThat(decision.isRequiresConfirmation()).isTrue();
        assertThat(decision.getRequirementPatch().getBudgetAmount()).isEqualByComparingTo("900");
        assertThat(decision.getRequirementPatch().getBudgetCurrency()).isEqualTo("EUR");
        assertThat(decision.getRequirementPatch().getAccommodationPreference()).isEqualTo("经济型酒店");
    }

    /**
     * 普通反馈不应生成新版本。
     */
    @Test
    void decideByRulesDetectsDirectComment() {
        PlanModificationDecision decision =
                PlanModificationAgent.decideByRules("这个方案不错，我先看看");

        assertThat(decision.getIntent()).isEqualTo(PlanModificationIntent.DIRECT_COMMENT);
    }

    /**
     * 表达过于模糊时应追问。
     */
    @Test
    void decideByRulesDetectsClarification() {
        PlanModificationDecision decision = PlanModificationAgent.decideByRules("帮我改改");

        assertThat(decision.getIntent()).isEqualTo(PlanModificationIntent.CLARIFICATION);
        assertThat(decision.getClarificationQuestion()).contains("修改");
    }

    /**
     * 模型失败时应回落到规则识别结果。
     */
    @Test
    void decideFallsBackToRulesWhenModelFails() {
        PlanModificationAgent agent = new PlanModificationAgent(mock(ChatClient.class), new ObjectMapper()) {
            @Override
            protected String callModel(com.travel.agent.ai.graph.model.TravelPlanRecord record,
                                       String userInstruction) {
                throw new RuntimeException("model down");
            }
        };

        PlanModificationDecision decision = agent.decide(null, "预算改成900欧");

        assertThat(decision.getIntent()).isEqualTo(PlanModificationIntent.REQUIREMENT_CHANGE);
        assertThat(decision.getRequirementPatch().getBudgetAmount()).isEqualByComparingTo("900");
    }
}
