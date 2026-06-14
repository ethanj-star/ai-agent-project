package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.BranchTask;
import com.travel.agent.ai.graph.model.BranchTaskType;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 分支任务派发节点。
 *
 * <p>系统架构位置：RetrieveKnowledgeNode -> <b>BranchDispatchNode</b> -> BranchExecuteNode</p>
 *
 * <p>职责：
 * <ul>
 *   <li>读取 {@link TravelPlanState} 中的用户原文、结构化需求表、目的地、时间和关键词。</li>
 *   <li>用低成本 Java 规则生成天气、景点、知识库、航班或酒店分支任务。</li>
 *   <li>只写入 branchTasks，不调用模型或外部 API，保持编排和执行职责分离。</li>
 *   <li>识别“不含机票”等否定语义，避免用户只是排除预算项时误触发航班分支。</li>
 *   <li>只在用户明确需要当前/实时天气时派发 WEATHER，避免把当前天气误用于未来旅行。</li>
 * </ul>
 * </p>
 */
@Component
public class BranchDispatchNode {

    private static final Logger log = LoggerFactory.getLogger(BranchDispatchNode.class);

    /**
     * 根据当前状态生成分支任务。
     *
     * @param state 当前旅行规划状态
     * @return 写入 branchTasks 后的状态
     */
    public TravelPlanState dispatch(TravelPlanState state) {
        // Facade 正常不会传 null；这里是节点级防御，保证单测或异常路径也能返回可继续流转的状态。
        if (state == null) {
            TravelPlanState fallback = new TravelPlanState();
            fallback.setBranchTasks(new ArrayList<>());
            return fallback;
        }

        List<BranchTask> tasks = new ArrayList<>();
        // 有目的地时默认补一条知识库任务，为 Planner 提供防坑、交通和经验类上下文。
        if (hasDestinations(state)) {
            tasks.add(buildTask("knowledge-1", BranchTaskType.KNOWLEDGE, state));
        }
        // 只有明确问“当前/今天/实时天气”才派天气任务，避免把今天的天气误用于未来行程。
        if (hasDestinations(state) && hasRealtimeWeatherNeed(state)) {
            tasks.add(buildTask("weather-1", BranchTaskType.WEATHER, state));
        }
        // 行程、景点、小众、人流等语义会触发景点分支，用来给 Planner 增加 POI 线索。
        if (hasDestinations(state) && looksLikePlacesNeed(state)) {
            tasks.add(buildTask("places-1", BranchTaskType.PLACES, state));
        }
        // 酒店分支在用户提到住宿，或需求表已经具备预算/日期/天数时触发，用真实价格辅助预算判断。
        if (hasDestinations(state) && shouldDispatchHotel(state)) {
            tasks.add(buildTask("hotel-1", BranchTaskType.HOTEL, state));
        }
        // 航班任务只在正向查询或结构化需求表具备完整航班查询骨架时触发。
        if (shouldDispatchFlight(state)) {
            tasks.add(buildTask("flight-1", BranchTaskType.FLIGHT, state));
        }

        state.setBranchTasks(tasks);
        log.info("[Graph][BranchDispatch] tasks={}, types={}",
                tasks.size(),
                tasks.stream().map(BranchTask::getType).toList());
        return state;
    }

    private static BranchTask buildTask(String taskId, BranchTaskType type, TravelPlanState state) {
        // BranchTask 是 Graph 与分支 Agent 之间的稳定协议。
        // 第十二阶段开始，航班和酒店工具需要明确日期、天数和出发地，因此从需求表同步强类型字段。
        TravelRequirementSpec spec = state.getRequirementSpec();
        return new BranchTask(
                taskId,
                type,
                state.getUserQuery(),
                state.getDestinations(),
                state.getTravelTime(),
                state.getKeywords(),
                spec == null ? null : spec.getStartDate(),
                state.getDurationDays(),
                spec == null ? null : spec.getDepartureCity(),
                spec == null ? null : spec.getAccommodationPreference(),
                spec == null ? null : spec.getBudgetIncludesInternationalFlight());
    }

    private static boolean hasDestinations(TravelPlanState state) {
        return state.getDestinations() != null && !state.getDestinations().isEmpty();
    }

    private static boolean looksLikePlacesNeed(TravelPlanState state) {
        // 规则只负责低成本粗判；真正的景点质量由 PlacesTools / Planner 后续处理。
        String text = joinedText(state);
        return text.contains("景点")
                || text.contains("地方")
                || text.contains("游玩")
                || text.contains("安排")
                || text.contains("行程")
                || text.contains("旅游")
                || text.contains("攻略")
                || text.contains("小众")
                || text.contains("人多")
                || text.contains("避开");
    }

    /**
     * 判断是否应该派发酒店分支。
     *
     * <p>酒店真实查询依赖目的地、日期和天数。用户明确提到住宿时会派发；
     * 已确认需求表中有预算、日期和天数时，也可以派发酒店分支作为预算参考。</p>
     */
    private static boolean shouldDispatchHotel(TravelPlanState state) {
        if (looksLikeHotelNeed(state)) {
            return true;
        }
        TravelRequirementSpec spec = state.getRequirementSpec();
        return spec != null
                && spec.getStartDate() != null
                && spec.getDurationDays() != null
                && spec.getBudgetAmount() != null;
    }

    private static boolean looksLikeHotelNeed(TravelPlanState state) {
        String text = joinedText(state).toLowerCase(Locale.ROOT);
        TravelRequirementSpec spec = state.getRequirementSpec();
        return hasText(spec == null ? null : spec.getAccommodationPreference())
                || containsAny(text,
                "酒店",
                "住宿",
                "住哪里",
                "住哪",
                "民宿",
                "青旅",
                "旅舍",
                "hostel",
                "hotel",
                "accommodation");
    }

    /**
     * 判断是否应该调用实时天气工具。
     *
     * <p>当前 WeatherTools 接的是 OpenWeatherMap Current Weather，只能回答当前天气。
     * “国庆”“下个月”“10月”这类未来旅行时间不应触发实时天气分支，否则 Planner 可能把今天的天气误当作出行当天依据。</p>
     */
    private static boolean hasRealtimeWeatherNeed(TravelPlanState state) {
        // 同时看用户原文和 travelTime，因为 Gatekeeper 可能把“今天”抽到 time 字段里。
        String text = joinedText(state).toLowerCase(Locale.ROOT);
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

    /**
     * 判断用户是否真的需要航班分支。
     *
     * <p>“不含国际机票”“预算不包括机票”这类表达只是在说明预算边界，
     * 不是请求系统查询航班，因此必须先识别否定/排除语义，再判断正向航班需求。</p>
     */
    private static boolean shouldDispatchFlight(TravelPlanState state) {
        if (!hasDestinations(state)) {
            return false;
        }
        String text = joinedText(state).toLowerCase(Locale.ROOT);

        // “不用查机票”这类表达是真正拒绝工具调用，优先级高于结构化字段。
        if (containsAny(text,
                "不用查机票",
                "不查机票",
                "不用查航班",
                "不查航班",
                "不需要查机票",
                "不需要查航班",
                "不要查机票",
                "不要查航班")) {
            return false;
        }

        // “不含国际机票”只是预算口径，不是正向航班查询需求；如果后面有完整出发地和日期，仍可作为路线参考查询。
        boolean budgetBoundaryOnly = containsAny(text,
                "不含机票",
                "不含国际机票",
                "不包含机票",
                "不包括机票",
                "不含航班",
                "不包含航班",
                "不包括航班",
                "机票自理",
                "国际机票自理");
        if (!budgetBoundaryOnly && containsAny(text, "机票", "航班", "机场", "flight")) {
            return true;
        }
        return hasStructuredFlightSkeleton(state);
    }

    private static boolean hasStructuredFlightSkeleton(TravelPlanState state) {
        TravelRequirementSpec spec = state.getRequirementSpec();
        return spec != null
                && hasText(spec.getDepartureCity())
                && spec.getStartDate() != null
                && spec.getDestinations() != null
                && !spec.getDestinations().isEmpty();
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

    private static String joinedText(TravelPlanState state) {
        StringBuilder builder = new StringBuilder();
        // 用户原文保留最多语义，keywords 则补充 Gatekeeper 抽出的短标签。
        if (hasText(state.getUserQuery())) {
            builder.append(state.getUserQuery()).append(' ');
        }
        if (state.getKeywords() != null) {
            builder.append(String.join(" ", state.getKeywords()));
        }
        return builder.toString();
    }

    private static String defaultText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
