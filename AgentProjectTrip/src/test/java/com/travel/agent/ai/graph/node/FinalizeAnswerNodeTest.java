package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.PlannerDraft;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.ValidationIssue;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FinalizeAnswerNode 的单元测试。
 *
 * <p>重点验证结构化 PlannerDraft 能被稳定拼装成 Markdown，并且校验问题会显式展示给用户。</p>
 */
class FinalizeAnswerNodeTest {

    private final FinalizeAnswerNode node = new FinalizeAnswerNode();

    /**
     * 草案和校验问题都存在时，应输出完整章节和“需要确认或注意的信息”。
     */
    @Test
    void finishBuildsMarkdownAnswerWithIssues() {
        PlannerDraft draft = new PlannerDraft();
        draft.setTitle("法国意大利10天");
        draft.setSummary("轻松游");
        draft.setItineraryMarkdown("Day 1 Paris");
        draft.setBudgetNotes("预算需要复核");
        draft.setRiskNotes("注意预约");
        draft.setAssumptions(List.of("假设从都柏林出发"));

        TravelPlanState state = new TravelPlanState();
        state.setDraft(draft);
        state.setDestinations(List.of("法国", "意大利"));
        state.setDurationDays(10);
        state.setDurationText("10天");
        state.setValidationIssues(List.of(
                ValidationIssue.medium("MISSING_DATE", "用户没有提供明确出行时间。")));

        TravelPlanState result = node.finish(state);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getFinalAnswer()).contains("# 法国意大利10天");
        assertThat(result.getFinalAnswer()).contains("## 推荐行程");
        assertThat(result.getFinalAnswer()).contains("## 已确认信息");
        assertThat(result.getFinalAnswer()).contains("行程时长：10天");
        assertThat(result.getFinalAnswer()).contains("需要确认或注意的信息");
        assertThat(result.getFinalAnswer()).contains("用户没有提供明确出行时间。");
    }

    /**
     * 草案为空时，节点应返回友好降级文本，而不是抛出空指针。
     */
    @Test
    void finishFallsBackWhenDraftIsMissing() {
        TravelPlanState state = node.finish(new TravelPlanState());

        assertThat(state.isSuccess()).isFalse();
        assertThat(state.getFinalAnswer()).contains("没有生成可用的旅行规划草案");
    }
}
