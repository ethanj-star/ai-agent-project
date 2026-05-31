package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.PlannerDraft;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.ValidationIssue;
import com.travel.agent.ai.graph.model.WorkflowStatus;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 最终答案拼装节点（Graph 层 - Markdown 输出器）。
 *
 * <p>系统架构位置：LangGraphPlannerFacade → <b>FinalizeAnswerNode</b> → GraphResult</p>
 *
 * <p>职责：
 * <ul>
 *   <li>把 {@link PlannerDraft} 中的结构化字段拼装成用户可读的 Markdown。</li>
 *   <li>把 Validator 输出的问题显式展示给用户，避免系统假装信息完整。</li>
 *   <li>在草案缺失时输出友好降级文本，保证 API 始终有可读响应。</li>
 * </ul>
 * </p>
 *
 * <p>设计取舍：第一阶段 Finalizer 不调用模型二次润色，先保证输出稳定、可测试。</p>
 */
@Component
public class FinalizeAnswerNode {

    /**
     * 生成最终 Markdown 答案。
     *
     * <p>处理流程：
     * <ol>
     *   <li>检查状态和 draft 是否存在；缺失时写入降级回复。</li>
     *   <li>按固定章节拼装标题、总体思路、推荐行程、预算提醒和风险提醒。</li>
     *   <li>追加当前假设和校验问题，让用户知道哪些信息仍需确认。</li>
     *   <li>写入 finalAnswer，并标记状态成功。</li>
     * </ol>
     * </p>
     *
     * @param state 当前旅行规划状态
     * @return 写入 finalAnswer 后的状态
     */
    public TravelPlanState finish(TravelPlanState state) {
        if (state == null) {
            TravelPlanState fallback = new TravelPlanState();
            fallback.setFinalAnswer("抱歉，规划流程暂时没有可用状态。请补充目的地、时间和预算后再试。");
            fallback.setSuccess(false);
            fallback.setErrorMessage("TravelPlanState is null");
            fallback.setWorkflowStatus(WorkflowStatus.FAILED);
            return fallback;
        }

        PlannerDraft draft = state.getDraft();
        if (draft == null) {
            state.setFinalAnswer("抱歉，本次没有生成可用的旅行规划草案。请补充目的地、时间和预算后再试。");
            state.setSuccess(false);
            state.setErrorMessage("PlannerDraft is null");
            state.setWorkflowStatus(WorkflowStatus.FAILED);
            return state;
        }

        StringBuilder answer = new StringBuilder();
        answer.append("# ").append(defaultText(draft.getTitle(), "欧洲旅行规划草案")).append("\n\n");

        appendSection(answer, "总体思路", draft.getSummary());
        appendSection(answer, "推荐行程", draft.getItineraryMarkdown());
        appendSection(answer, "预算与预订提醒", draft.getBudgetNotes());
        appendSection(answer, "防坑与风险提醒", draft.getRiskNotes());
        appendAssumptions(answer, draft.getAssumptions());
        appendValidationIssues(answer, state.getValidationIssues());

        state.setFinalAnswer(answer.toString().trim());
        state.setSuccess(true);
        state.setWorkflowStatus(WorkflowStatus.COMPLETED);
        return state;
    }

    /**
     * 追加一个 Markdown 二级章节。
     *
     * <p>空内容不输出对应标题，避免最终答案出现空章节。</p>
     */
    private static void appendSection(StringBuilder answer, String title, String content) {
        if (!hasText(content)) {
            return;
        }
        answer.append("## ").append(title).append("\n");
        answer.append(content.trim()).append("\n\n");
    }

    /**
     * 输出 Planner 因用户信息不足而做出的假设。
     *
     * <p>显式暴露假设可以减少用户误以为系统已经确认事实的风险。</p>
     */
    private static void appendAssumptions(StringBuilder answer, List<String> assumptions) {
        if (assumptions == null || assumptions.isEmpty()) {
            return;
        }

        answer.append("## 当前假设\n");
        for (String assumption : assumptions) {
            if (hasText(assumption)) {
                answer.append("- ").append(assumption.trim()).append("\n");
            }
        }
        answer.append("\n");
    }

    /**
     * 输出 Validator 发现的问题。
     *
     * <p>第一阶段不做自动修正循环，因此这里直接把问题展示给用户；
     * 后续阶段可根据 severity 决定是否回到 Planner 或追问用户。</p>
     */
    private static void appendValidationIssues(StringBuilder answer, List<ValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return;
        }

        answer.append("## 需要确认或注意的信息\n");
        for (ValidationIssue issue : issues) {
            if (issue == null || !hasText(issue.getMessage())) {
                continue;
            }
            answer.append("- [")
                    .append(defaultText(issue.getSeverity(), "INFO"))
                    .append("] ")
                    .append(issue.getMessage().trim())
                    .append("\n");
        }
        answer.append("\n");
    }

    /**
     * 返回非空文本，否则返回兜底文本。
     */
    private static String defaultText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    /**
     * 判断字符串是否包含非空白文本。
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
