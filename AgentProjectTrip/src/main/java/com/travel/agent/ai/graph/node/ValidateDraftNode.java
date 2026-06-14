package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.PlannerDraft;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.ValidationIssue;
import com.travel.agent.ai.graph.model.WorkflowStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 规划草案校验节点（Graph 层 - 规则校验器）。
 *
 * <p>系统架构位置：LangGraphPlannerFacade → <b>ValidateDraftNode</b> → FinalizeAnswerNode</p>
 *
 * <p>职责：
 * <ul>
 *   <li>对 PlannerDraft 进行第一阶段的确定性 Java 规则校验。</li>
 *   <li>识别目的地、日期、行程时长、预算、草案内容和 RAG 上下文等基础缺口。</li>
 *   <li>输出结构化 {@link ValidationIssue} 列表，供 Finalizer 展示给用户。</li>
 * </ul>
 * </p>
 *
 * <p>设计取舍：第一阶段不调用模型做反思校验，先用可测试、可预测的规则打底；
 * 第二阶段再将这些问题作为循环修正或追问用户的依据。</p>
 */
@Component
public class ValidateDraftNode {

    /**
     * 校验当前规划状态和草案。
     *
     * <p>校验项：
     * <ul>
     *   <li>目的地是否缺失。</li>
     *   <li>出行时间是否缺失。</li>
     *   <li>行程时长是否缺失。</li>
     *   <li>用户提到预算时，草案是否包含预算说明。</li>
     *   <li>草案是否为空或过短。</li>
     *   <li>RAG 上下文是否不足。</li>
     * </ul>
     * </p>
     *
     * @param state 当前旅行规划状态
     * @return 写入 validationIssues 后的状态
     */
    public TravelPlanState validate(TravelPlanState state) {
        // 校验节点也做 null-safe，避免上游失败时继续抛空指针。
        if (state == null) {
            TravelPlanState fallback = new TravelPlanState();
            fallback.setValidationIssues(List.of(
                    ValidationIssue.high("EMPTY_STATE", "规划状态为空，无法校验草案。")));
            fallback.setWorkflowStatus(WorkflowStatus.FAILED);
            return fallback;
        }

        List<ValidationIssue> issues = new ArrayList<>();
        PlannerDraft draft = state.getDraft();

        // 目的地是旅行规划的硬前提；缺失时标记为 HIGH，后续阶段可转为追问用户
        if (state.getDestinations() == null || state.getDestinations().isEmpty()) {
            issues.add(ValidationIssue.high("MISSING_DESTINATION", "用户没有提供明确目的地。"));
        } else if (hasOnlyBroadDestination(state.getDestinations())) {
            issues.add(ValidationIssue.medium("BROAD_DESTINATION",
                    "用户只提供了较宽泛的目的地范围，建议补充具体国家或城市。"));
        }

        // 时间缺失不会阻止生成草案，但会影响天气、价格和预约建议，因此标记为 MEDIUM
        if (!hasText(state.getTravelTime()) || "未指定".equals(state.getTravelTime())) {
            issues.add(ValidationIssue.medium("MISSING_DATE", "用户没有提供明确出行时间。"));
        }

        if (state.getDurationDays() == null && looksLikeItineraryPlanning(state)) {
            issues.add(ValidationIssue.medium("MISSING_DURATION", "用户没有提供明确行程天数。"));
        }

        if (draft == null) {
            // 没有草案是高风险，Finalizer 无法正常拼装最终答案。
            issues.add(ValidationIssue.high("EMPTY_DRAFT", "规划草案为空，无法形成可靠行程。"));
        } else {
            if (mentionsBudget(state.getUserQuery()) && !hasText(draft.getBudgetNotes())) {
                // 用户明确提预算时，预算说明就不是可选项。
                issues.add(ValidationIssue.high("BUDGET_NOT_ADDRESSED", "用户提到了预算，但草案没有预算说明。"));
            }

            if (!hasText(draft.getItineraryMarkdown())) {
                issues.add(ValidationIssue.high("EMPTY_DRAFT", "规划草案没有包含行程内容。"));
            } else if (draft.getItineraryMarkdown().length() < 120) {
                issues.add(ValidationIssue.medium("TOO_VAGUE", "行程草案较短，后续需要继续细化。"));
            }
        }

        if (!hasText(state.getRagContext()) || state.getRagContext().contains("暂无相关攻略")
                || state.getRagContext().contains("暂时不可用")) {
            // RAG 不足不阻塞流程，但最终答案需要提醒“本地经验可能不足”。
            issues.add(ValidationIssue.low("INSUFFICIENT_RAG", "私有知识库上下文不足，本次方案可能缺少本地经验。"));
        }

        state.setValidationIssues(issues);
        // 目前只有目的地缺失/过宽会阻塞并追问，其余问题交给 Finalizer 或 RiskReasoning 提示/修正。
        state.setWorkflowStatus(hasBlockingClarificationIssue(issues)
                ? WorkflowStatus.NEEDS_CLARIFICATION
                : WorkflowStatus.PLANNING);
        return state;
    }

    /**
     * 判断用户输入是否显式提到预算或价格。
     *
     * <p>第一阶段用关键词规则实现，避免为了简单预算识别再调用模型。</p>
     */
    static boolean mentionsBudget(String text) {
        if (!hasText(text)) {
            return false;
        }
        String normalized = text.toLowerCase(Locale.ROOT);
        return normalized.contains("预算")
                || normalized.contains("budget")
                || normalized.contains("eur")
                || normalized.contains("€")
                || normalized.contains("欧")
                || normalized.contains("价格")
                || normalized.contains("花费")
                || normalized.contains("多少钱");
    }

    /**
     * 判断当前请求是否像完整行程规划。
     *
     * <p>只有在用户明显要求“安排/规划/行程/路线”等场景下，缺少 duration 才提示。
     * 这样可以避免用户只是问“法国有哪些景点”时被不必要地追问旅行天数。</p>
     */
    private static boolean looksLikeItineraryPlanning(TravelPlanState state) {
        if (state == null) {
            return false;
        }
        StringBuilder text = new StringBuilder();
        if (hasText(state.getUserQuery())) {
            text.append(state.getUserQuery()).append(' ');
        }
        if (state.getKeywords() != null) {
            text.append(String.join(" ", state.getKeywords()));
        }
        String normalized = text.toString().toLowerCase(Locale.ROOT);
        return normalized.contains("安排")
                || normalized.contains("规划")
                || normalized.contains("行程")
                || normalized.contains("路线")
                || normalized.contains("怎么玩")
                || normalized.contains("旅游计划")
                || normalized.contains("itinerary")
                || normalized.contains("plan");
    }

    /**
     * 判断目的地是否只有“欧洲 / 国外 / 海外”这类宽泛范围。
     *
     * <p>Gatekeeper 会把“我想去欧洲玩”提取为 locations=["欧洲"]。这不应被视为
     * 完整目的地信息，否则系统会在缺少国家和城市偏好的情况下直接生成过于随意的方案。</p>
     */
    private static boolean hasOnlyBroadDestination(List<String> destinations) {
        if (destinations == null || destinations.isEmpty()) {
            return false;
        }
        for (String destination : destinations) {
            if (!isBroadDestination(destination)) {
                return false;
            }
        }
        return true;
    }

    /**
     * 判断当前问题是否需要暂停规划并追问用户。
     *
     * <p>第二阶段先只把“目的地缺失”和“目的地过宽”视为阻塞问题。预算、日期、时长、RAG 不足等问题仍然可以在最终答案里提示，
     * 避免系统因为非关键缺口频繁打断用户。</p>
     */
    private static boolean hasBlockingClarificationIssue(List<ValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return false;
        }
        for (ValidationIssue issue : issues) {
            if (issue == null || !hasText(issue.getCode())) {
                continue;
            }
            if ("MISSING_DESTINATION".equals(issue.getCode())
                    || "BROAD_DESTINATION".equals(issue.getCode())) {
                return true;
            }
        }
        return false;
    }

    /**
     * 判断单个目的地是否属于宽泛区域描述。
     */
    private static boolean isBroadDestination(String destination) {
        if (!hasText(destination)) {
            return true;
        }
        String normalized = destination.trim();
        return normalized.equals("欧洲")
                || normalized.equals("欧州")
                || normalized.equals("国外")
                || normalized.equals("海外")
                || normalized.equals("境外")
                || normalized.equals("随便")
                || normalized.equals("都可以");
    }

    /**
     * 判断字符串是否包含非空白文本。
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
