package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.ClarificationQuestion;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.ValidationIssue;
import com.travel.agent.ai.graph.model.WorkflowStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 澄清追问节点。
 *
 * <p>系统架构位置：ValidateDraftNode -> <b>ClarifyQuestionNode</b> -> ConversationStateStore</p>
 *
 * <p>职责：
 * <ul>
 *   <li>读取 Validator 输出的阻塞性问题。</li>
 *   <li>把机器可读的 ValidationIssue 转换成用户可读的追问。</li>
 *   <li>一次最多输出三个问题，避免用户被过多问题打断。</li>
 *   <li>把追问写回 TravelPlanState，供 Facade 保存 pending 状态。</li>
 * </ul>
 * </p>
 */
@Component
public class ClarifyQuestionNode {

    private static final int MAX_QUESTIONS = 3;

    /**
     * 根据当前校验问题生成澄清追问。
     *
     * @param state 当前旅行规划状态
     * @return 写入 pendingQuestions 和 finalAnswer 后的状态
     */
    public TravelPlanState ask(TravelPlanState state) {
        if (state == null) {
            TravelPlanState fallback = new TravelPlanState();
            fallback.setWorkflowStatus(WorkflowStatus.NEEDS_CLARIFICATION);
            fallback.setPendingQuestions(List.of(genericQuestion()));
            fallback.setFinalAnswer(buildAnswer(fallback.getPendingQuestions()));
            fallback.setSuccess(true);
            return fallback;
        }

        List<ClarificationQuestion> questions = buildQuestions(state.getValidationIssues());
        if (questions.isEmpty()) {
            questions.add(genericQuestion());
        }

        state.setPendingQuestions(questions);
        state.setWorkflowStatus(WorkflowStatus.NEEDS_CLARIFICATION);
        state.setFinalAnswer(buildAnswer(questions));
        state.setSuccess(true);
        return state;
    }

    /**
     * 把 ValidationIssue 映射为结构化追问。
     */
    private static List<ClarificationQuestion> buildQuestions(List<ValidationIssue> issues) {
        List<ClarificationQuestion> questions = new ArrayList<>();
        Set<String> addedFields = new LinkedHashSet<>();
        if (issues == null || issues.isEmpty()) {
            return questions;
        }

        for (ValidationIssue issue : issues) {
            ClarificationQuestion question = questionForIssue(issue);
            if (question == null || addedFields.contains(question.getField())) {
                continue;
            }
            questions.add(question);
            addedFields.add(question.getField());
            if (questions.size() >= MAX_QUESTIONS) {
                break;
            }
        }
        return questions;
    }

    /**
     * 根据问题 code 生成固定追问，保证测试稳定、行为可预期。
     */
    private static ClarificationQuestion questionForIssue(ValidationIssue issue) {
        if (issue == null || !hasText(issue.getCode())) {
            return null;
        }
        return switch (issue.getCode()) {
            case "MISSING_DESTINATION", "BROAD_DESTINATION" ->
                    new ClarificationQuestion(
                            "destination_scope",
                            "destinations",
                            "你更想去哪些国家或城市？如果不确定，我可以按经典路线、自然风景或小众避人流来帮你选。",
                            true);
            case "MISSING_DATE" ->
                    new ClarificationQuestion(
                            "travel_time",
                            "travelTime",
                            "你的出行日期或大致月份是什么？如果还没定，也可以告诉我季节或假期范围。",
                            true);
            case "MISSING_DURATION" ->
                    new ClarificationQuestion(
                            "travel_duration",
                            "duration",
                            "你这次大概想玩几天？例如 5天、10天，或者一周左右。",
                            true);
            case "MISSING_BUDGET" ->
                    new ClarificationQuestion(
                            "budget_scope",
                            "budget",
                            "你的预算大概是多少？这个预算是否包含国际往返机票？",
                            true);
            default -> null;
        };
    }

    /**
     * 构造兜底追问。
     */
    private static ClarificationQuestion genericQuestion() {
        return new ClarificationQuestion(
                "general_clarification",
                "general",
                "你可以补充一下目的地、出行天数、预算和旅行偏好吗？",
                true);
    }

    /**
     * 把结构化问题渲染成当前 API 可直接返回的 Markdown 文本。
     */
    private static String buildAnswer(List<ClarificationQuestion> questions) {
        StringBuilder answer = new StringBuilder();
        answer.append("可以，我先确认几个关键信息，这样后面规划会更准：\n\n");
        int index = 1;
        for (ClarificationQuestion question : questions) {
            if (question != null && hasText(question.getQuestion())) {
                answer.append(index++).append(". ").append(question.getQuestion().trim()).append("\n");
            }
        }
        answer.append("\n你直接按自然语言回答就可以，我会接着刚才的任务继续规划。");
        return answer.toString().trim();
    }

    /**
     * 判断字符串是否包含非空白文本。
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
