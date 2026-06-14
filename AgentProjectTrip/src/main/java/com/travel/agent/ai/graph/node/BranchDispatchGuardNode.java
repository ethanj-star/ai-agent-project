package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.BranchDispatchDecision;
import com.travel.agent.ai.graph.model.BranchDispatchIssue;
import com.travel.agent.ai.graph.model.BranchDispatchPolicy;
import com.travel.agent.ai.graph.model.BranchTask;
import com.travel.agent.ai.graph.model.BranchTaskSuggestion;
import com.travel.agent.ai.graph.model.BranchTaskType;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 分支派发安全守卫节点。
 *
 * <p>系统架构位置：ModelBranchDispatchNode -> <b>BranchDispatchGuardNode</b> -> BranchExecuteNode</p>
 *
 * <p>职责：
 * <ul>
 *   <li>读取模型输出的 {@link BranchDispatchDecision}。</li>
 *   <li>校验任务类型、数量、必要参数和工具边界。</li>
 *   <li>把合法模型建议转换为 {@link BranchTask} 并写入 {@link TravelPlanState#setBranchTasks(List)}。</li>
 *   <li>记录接受、拒绝、裁剪和 fallback 原因，供日志与后续 Trace 使用。</li>
 *   <li>模型派发失败时调用旧 {@link BranchDispatchNode} 作为规则兜底。</li>
 * </ul>
 * </p>
 *
 * <p>设计边界：模型可以建议，但不能越过本节点直接执行工具。
 * 这保证了新增外部工具、计费和实时数据边界仍由 Java 代码掌握。</p>
 */
@Component
public class BranchDispatchGuardNode {

    private static final Logger log = LoggerFactory.getLogger(BranchDispatchGuardNode.class);

    /** 旧 Java 规则派发节点；模型失败或建议为空时作为稳定 fallback。 */
    private final BranchDispatchNode ruleBasedDispatchNode;

    /** 第一版固定策略：允许 5 类任务，最多执行 5 个。 */
    private final BranchDispatchPolicy policy;

    @Autowired
    public BranchDispatchGuardNode(BranchDispatchNode ruleBasedDispatchNode) {
        this(ruleBasedDispatchNode, BranchDispatchPolicy.defaultPolicy());
    }

    BranchDispatchGuardNode(BranchDispatchNode ruleBasedDispatchNode, BranchDispatchPolicy policy) {
        this.ruleBasedDispatchNode = ruleBasedDispatchNode;
        this.policy = policy == null ? BranchDispatchPolicy.defaultPolicy() : policy;
    }

    /**
     * 校验模型建议并写入最终可执行分支任务。
     *
     * <p>处理流程：</p>
     * <ol>
     *   <li>如果模型要求 fallback，直接走旧 BranchDispatchNode。</li>
     *   <li>按优先级遍历模型建议，拒绝未知类型、重复类型和参数不足任务。</li>
     *   <li>合法建议转成 BranchTask，写回 state.branchTasks。</li>
     *   <li>如果模型成功返回建议但全部被拒绝，则写入空任务列表，避免旧规则绕过安全拒绝。</li>
     * </ol>
     *
     * @param state    当前旅行规划状态
     * @param decision 模型分支派发建议
     * @return 写入 branchTasks、branchDispatchDecision 和 branchDispatchIssues 后的状态
     */
    public TravelPlanState guard(TravelPlanState state, BranchDispatchDecision decision) {
        TravelPlanState safeState = state == null ? new TravelPlanState() : state;
        BranchDispatchDecision safeDecision = decision == null
                ? BranchDispatchDecision.fallback("模型派发结果为空。")
                : decision;
        safeState.setBranchDispatchDecision(safeDecision);

        List<BranchDispatchIssue> issues = new ArrayList<>();
        if (safeDecision.isFallbackRequired() || safeDecision.getTasks().isEmpty()) {
            String reason = hasText(safeDecision.getFallbackReason())
                    ? safeDecision.getFallbackReason()
                    : "模型未返回可执行任务。";
            issues.add(BranchDispatchIssue.fallback(reason));
            return fallbackToRuleBasedDispatch(safeState, issues);
        }

        List<BranchTask> acceptedTasks = new ArrayList<>();
        Set<BranchTaskType> acceptedTypes = EnumSet.noneOf(BranchTaskType.class);
        List<BranchTaskSuggestion> orderedSuggestions = safeDecision.getTasks().stream()
                .filter(suggestion -> suggestion != null)
                .sorted(Comparator.comparingInt(BranchDispatchGuardNode::priorityRank).reversed())
                .toList();

        for (BranchTaskSuggestion suggestion : orderedSuggestions) {
            BranchTaskType type = parseType(suggestion.normalizedType());
            String rawType = hasText(suggestion.getType()) ? suggestion.getType() : suggestion.normalizedType();

            if (type == null) {
                issues.add(BranchDispatchIssue.rejected(rawType, "模型建议了当前系统不存在或未接入的工具类型。"));
                continue;
            }
            if (!policy.isAllowedType(type)) {
                issues.add(BranchDispatchIssue.rejected(type.name(), "该任务类型不在当前阶段白名单内。"));
                continue;
            }
            if (acceptedTypes.contains(type)) {
                issues.add(BranchDispatchIssue.rejected(type.name(), "同一任务类型本阶段最多执行一次。"));
                continue;
            }
            if (acceptedTasks.size() >= policy.maxTaskCount()) {
                issues.add(BranchDispatchIssue.trimmed(type.name(), "模型建议任务超过本阶段最大执行数量。"));
                continue;
            }

            String rejectReason = validateSuggestion(safeState, type);
            if (hasText(rejectReason)) {
                issues.add(BranchDispatchIssue.rejected(type.name(), rejectReason));
                continue;
            }

            BranchTask task = toBranchTask(safeState, suggestion, type, acceptedTasks.size() + 1);
            acceptedTasks.add(task);
            acceptedTypes.add(type);
            issues.add(BranchDispatchIssue.accepted(type.name(), defaultText(suggestion.getReason(), "模型建议执行该分支任务。")));
        }

        safeState.setBranchTasks(acceptedTasks);
        safeState.setBranchDispatchIssues(issues);
        log.info("[Graph][BranchDispatchGuard] accepted={}, rejectedOrTrimmed={}, types={}",
                acceptedTasks.size(),
                issues.stream().filter(issue -> !"ACCEPTED".equals(issue.getAction())).count(),
                acceptedTasks.stream().map(BranchTask::getType).toList());
        return safeState;
    }

    private TravelPlanState fallbackToRuleBasedDispatch(TravelPlanState state, List<BranchDispatchIssue> issues) {
        TravelPlanState dispatchedState = ruleBasedDispatchNode == null ? state : ruleBasedDispatchNode.dispatch(state);
        dispatchedState.setBranchDispatchIssues(issues);
        log.info("[Graph][BranchDispatchGuard] fallback to rule-based dispatch, tasks={}, reason={}",
                dispatchedState.getBranchTasks() == null ? 0 : dispatchedState.getBranchTasks().size(),
                issues.isEmpty() ? "unknown" : issues.get(issues.size() - 1).getReason());
        return dispatchedState;
    }

    private static BranchTask toBranchTask(TravelPlanState state,
                                           BranchTaskSuggestion suggestion,
                                           BranchTaskType type,
                                           int index) {
        TravelRequirementSpec spec = state.getRequirementSpec();
        return new BranchTask(
                type.name().toLowerCase(Locale.ROOT) + "-model-" + index,
                type,
                state.getUserQuery(),
                destinationsForTask(state),
                state.getTravelTime(),
                state.getKeywords(),
                startDateForTask(state),
                durationForTask(state),
                spec == null ? null : spec.getDepartureCity(),
                spec == null ? null : spec.getAccommodationPreference(),
                spec == null ? null : spec.getBudgetIncludesInternationalFlight(),
                suggestion == null ? null : suggestion.getReason(),
                suggestion == null ? null : suggestion.getPriority());
    }

    private static String validateSuggestion(TravelPlanState state, BranchTaskType type) {
        return switch (type) {
            case KNOWLEDGE -> validateKnowledge(state);
            case PLACES -> validatePlaces(state);
            case WEATHER -> validateWeather(state);
            case FLIGHT -> validateFlight(state);
            case HOTEL -> validateHotel(state);
        };
    }

    private static String validateKnowledge(TravelPlanState state) {
        if (hasDestinations(state) || containsAny(joinedText(state), "规划", "安排", "行程", "攻略", "旅游")) {
            return null;
        }
        return "缺少目的地或复杂规划语义，知识分支暂不需要执行。";
    }

    private static String validatePlaces(TravelPlanState state) {
        return hasDestinations(state) ? null : "景点分支依赖目的地，当前没有可用目的地。";
    }

    private static String validateWeather(TravelPlanState state) {
        if (!hasDestinations(state)) {
            return "天气分支依赖目的地，当前没有可用目的地。";
        }
        if (!hasRealtimeWeatherNeed(state)) {
            return "当前 WeatherTools 只支持实时天气，不能用于未来旅行天气预报。";
        }
        return null;
    }

    private static String validateFlight(TravelPlanState state) {
        TravelRequirementSpec spec = state.getRequirementSpec();
        if (!hasDestinations(state)) {
            return "航班分支依赖目的地，当前没有可用目的地。";
        }
        if (spec == null || !hasText(spec.getDepartureCity())) {
            return "航班分支需要明确出发地 departureCity。";
        }
        if (spec.getStartDate() == null) {
            return "航班分支需要明确 startDate，不能用模糊时间查询实时机票。";
        }
        return null;
    }

    private static String validateHotel(TravelPlanState state) {
        if (!hasDestinations(state)) {
            return "酒店分支依赖目的地，当前没有可用目的地。";
        }
        if (startDateForTask(state) == null) {
            return "酒店分支需要明确入住日期 startDate。";
        }
        if (durationForTask(state) == null) {
            return "酒店分支需要明确旅行天数 durationDays，用于推导退房日期。";
        }
        return null;
    }

    private static BranchTaskType parseType(String normalizedType) {
        if (!hasText(normalizedType)) {
            return null;
        }
        try {
            return BranchTaskType.valueOf(normalizedType);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    private static int priorityRank(BranchTaskSuggestion suggestion) {
        String priority = suggestion == null || suggestion.getPriority() == null
                ? ""
                : suggestion.getPriority().trim().toUpperCase(Locale.ROOT);
        return switch (priority) {
            case "HIGH" -> 3;
            case "LOW" -> 1;
            default -> 2;
        };
    }

    private static List<String> destinationsForTask(TravelPlanState state) {
        TravelRequirementSpec spec = state.getRequirementSpec();
        if (spec != null && spec.getDestinations() != null && !spec.getDestinations().isEmpty()) {
            return spec.getDestinations();
        }
        return state.getDestinations();
    }

    private static LocalDate startDateForTask(TravelPlanState state) {
        TravelRequirementSpec spec = state.getRequirementSpec();
        return spec == null ? null : spec.getStartDate();
    }

    private static Integer durationForTask(TravelPlanState state) {
        TravelRequirementSpec spec = state.getRequirementSpec();
        if (state.getDurationDays() != null) {
            return state.getDurationDays();
        }
        return spec == null ? null : spec.getDurationDays();
    }

    private static boolean hasDestinations(TravelPlanState state) {
        List<String> destinations = destinationsForTask(state);
        return destinations != null && !destinations.isEmpty();
    }

    private static boolean hasRealtimeWeatherNeed(TravelPlanState state) {
        String text = joinedText(state);
        String travelTime = state == null ? "" : defaultText(state.getTravelTime(), "").toLowerCase(Locale.ROOT);
        return containsAny(text,
                "实时天气",
                "当前天气",
                "现在天气",
                "今天的天气",
                "今天天气",
                "此刻天气",
                "weather now",
                "current weather")
                || containsAny(travelTime,
                "今天",
                "现在",
                "当前",
                "此刻",
                "today",
                "now");
    }

    private static String joinedText(TravelPlanState state) {
        StringBuilder builder = new StringBuilder();
        if (state != null && hasText(state.getUserQuery())) {
            builder.append(state.getUserQuery()).append(' ');
        }
        if (state != null && state.getKeywords() != null) {
            builder.append(String.join(" ", state.getKeywords()));
        }
        return builder.toString().toLowerCase(Locale.ROOT);
    }

    private static boolean containsAny(String text, String... candidates) {
        if (!hasText(text) || candidates == null) {
            return false;
        }
        for (String candidate : candidates) {
            if (hasText(candidate) && text.contains(candidate.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static String defaultText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
