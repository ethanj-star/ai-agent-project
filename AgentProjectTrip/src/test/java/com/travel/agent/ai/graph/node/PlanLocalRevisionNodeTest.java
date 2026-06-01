package com.travel.agent.ai.graph.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.ai.graph.model.PlanLocalRevisionResult;
import com.travel.agent.ai.graph.model.PlanModificationDecision;
import com.travel.agent.ai.graph.model.TravelPlanRecord;
import com.travel.agent.ai.graph.model.TravelPlanVersion;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PlanLocalRevisionNode 的单元测试。
 *
 * <p>测试范围避开真实模型调用，只验证 prompt 构建、JSON 解析和空计划失败策略。</p>
 */
class PlanLocalRevisionNodeTest {

    private final PlanLocalRevisionNode node = new PlanLocalRevisionNode((ChatClient) null, new ObjectMapper());

    /**
     * 局部修改 prompt 应包含当前计划、需求表和不可破坏核心需求的约束。
     */
    @Test
    void buildSystemPromptContainsCurrentPlanAndRequirement() {
        TravelPlanRecord record = record();
        PlanModificationDecision decision = new PlanModificationDecision();
        decision.setTargetDay("第三天");
        decision.setTargetSections(List.of("itinerary"));
        decision.setInstructionSummary("第三天太赶，减少一个城市。");

        String prompt = node.buildSystemPrompt(record, record.current().orElseThrow(), decision);

        assertThat(prompt).contains("不是从零规划");
        assertThat(prompt).contains("不得改变已确认需求表");
        assertThat(prompt).contains("法国、意大利");
        assertThat(prompt).contains("第三天");
        assertThat(prompt).contains("当前计划文本");
    }

    /**
     * 模型返回合法 JSON 时应解析出新答案和修改摘要。
     */
    @Test
    void parseResultReadsAnswerAndSummary() {
        PlanLocalRevisionResult result = node.parseResult("""
                {
                  "answer": "# 修改后的计划\\n第三天更轻松。",
                  "modificationSummary": "放松第3天节奏"
                }
                """, new PlanModificationDecision());

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getAnswer()).contains("修改后的计划");
        assertThat(result.getModificationSummary()).isEqualTo("放松第3天节奏");
    }

    /**
     * 没有当前版本时应失败，调用方不应新增版本。
     */
    @Test
    void reviseFailsWhenCurrentPlanMissing() {
        PlanLocalRevisionResult result =
                node.revise(new TravelPlanRecord(), new PlanModificationDecision(), "第三天太赶");

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getErrorMessage()).contains("当前计划");
    }

    private static TravelPlanRecord record() {
        TravelRequirementSpec spec = new TravelRequirementSpec();
        spec.setDestinations(List.of("法国", "意大利"));
        spec.setStartDateText("国庆");
        spec.setDurationDays(10);
        spec.setBudgetAmount(BigDecimal.valueOf(1200));
        spec.setBudgetCurrency("EUR");

        TravelPlanVersion version = new TravelPlanVersion();
        version.setVersion(1);
        version.setFinalAnswer("# 当前计划文本\n第三天：巴黎到尼斯。");

        TravelPlanRecord record = new TravelPlanRecord();
        record.setPlanId("plan-1");
        record.setRequirementSpec(spec);
        record.addVersion(version);
        return record;
    }
}
