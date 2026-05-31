package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.ClarificationQuestion;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.ValidationIssue;
import com.travel.agent.ai.graph.model.WorkflowStatus;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * ClarifyQuestionNode 的单元测试。
 *
 * <p>重点验证第二阶段“阻塞性信息缺口 -> 用户追问”的稳定规则。</p>
 */
class ClarifyQuestionNodeTest {

    private final ClarifyQuestionNode node = new ClarifyQuestionNode();

    /**
     * 目的地过宽时，应生成目的地澄清问题并暂停工作流。
     */
    @Test
    void askBuildsDestinationQuestionForBroadDestination() {
        TravelPlanState state = new TravelPlanState();
        state.setValidationIssues(List.of(
                ValidationIssue.medium("BROAD_DESTINATION", "用户只提供了较宽泛的目的地范围。")));

        TravelPlanState result = node.ask(state);

        assertThat(result.getWorkflowStatus()).isEqualTo(WorkflowStatus.NEEDS_CLARIFICATION);
        assertThat(result.getPendingQuestions())
                .extracting(ClarificationQuestion::getField)
                .containsExactly("destinations");
        assertThat(result.getFinalAnswer()).contains("你更想去哪些国家或城市");
        assertThat(result.isSuccess()).isTrue();
    }

    /**
     * 一次追问最多输出三个问题，避免让用户一次性回答过多内容。
     */
    @Test
    void askLimitsQuestionCount() {
        TravelPlanState state = new TravelPlanState();
        state.setValidationIssues(List.of(
                ValidationIssue.high("MISSING_DESTINATION", "缺目的地"),
                ValidationIssue.medium("MISSING_DATE", "缺时间"),
                ValidationIssue.high("MISSING_BUDGET", "缺预算"),
                ValidationIssue.low("INSUFFICIENT_RAG", "RAG 不足")));

        TravelPlanState result = node.ask(state);

        assertThat(result.getPendingQuestions()).hasSize(3);
        assertThat(result.getFinalAnswer()).contains("1.").contains("2.").contains("3.");
    }
}
