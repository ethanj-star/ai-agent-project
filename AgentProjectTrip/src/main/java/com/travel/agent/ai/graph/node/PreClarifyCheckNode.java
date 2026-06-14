package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.ValidationIssue;
import com.travel.agent.ai.graph.model.WorkflowStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 前置澄清判断节点。
 *
 * <p>系统架构位置：InitStateNode / MergeClarificationNode -> <b>PreClarifyCheckNode</b> -> ClarifyQuestionNode 或 RAG</p>
 *
 * <p>职责：
 * <ul>
 *   <li>在 RAG 和核心 Planner 模型调用之前，先用低成本 Java 规则判断是否必须追问用户。</li>
 *   <li>拦截“没有目的地”或“目的地只有欧洲/国外/海外”等明显无法精确规划的请求。</li>
 *   <li>为第二阶段节约核心模型调用成本，并让用户先补齐关键约束。</li>
 * </ul>
 * </p>
 */
@Component
public class PreClarifyCheckNode {

    /**
     * 执行前置澄清判断。
     *
     * <p>第一版只判断目的地是否足够明确。预算、日期、偏好等信息仍交给 Planner 和 Validator 处理，
     * 避免系统因为非阻塞信息缺口频繁打断用户。</p>
     *
     * @param state 当前旅行规划状态
     * @return 写入 validationIssues 和 workflowStatus 后的状态
     */
    public TravelPlanState check(TravelPlanState state) {
        // 上游状态为空时，没有任何可规划信息，只能进入澄清。
        if (state == null) {
            TravelPlanState fallback = new TravelPlanState();
            fallback.setWorkflowStatus(WorkflowStatus.NEEDS_CLARIFICATION);
            fallback.setValidationIssues(List.of(
                    ValidationIssue.high("MISSING_DESTINATION", "用户没有提供明确目的地。")));
            return fallback;
        }

        List<ValidationIssue> issues = new ArrayList<>();
        // 目的地是规划的硬前提；没有目的地时，RAG 和 Planner 都很难给出可靠答案。
        if (state.getDestinations() == null || state.getDestinations().isEmpty()) {
            issues.add(ValidationIssue.high("MISSING_DESTINATION", "用户没有提供明确目的地。"));
        } else if (hasOnlyBroadDestination(state.getDestinations())) {
            // “欧洲/国外/随便”太宽泛，先追问比生成一个泛泛路线更有价值。
            issues.add(ValidationIssue.medium("BROAD_DESTINATION",
                    "用户只提供了较宽泛的目的地范围，建议补充具体国家或城市。"));
        }

        if (issues.isEmpty()) {
            // 前置检查只拦截硬性目的地缺口，其他缺口留给后续 Validator 判断是否阻塞。
            state.setWorkflowStatus(WorkflowStatus.PLANNING);
            return state;
        }

        // 有硬缺口时写入 validationIssues，后续 ClarifyQuestionNode 会把它转成用户追问。
        state.setValidationIssues(issues);
        state.setWorkflowStatus(WorkflowStatus.NEEDS_CLARIFICATION);
        return state;
    }

    /**
     * 判断目的地列表是否全部属于宽泛区域描述。
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
