package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.BranchTask;
import com.travel.agent.ai.graph.model.BranchTaskType;
import com.travel.agent.ai.graph.model.TravelPlanState;
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
 *   <li>读取 {@link TravelPlanState} 中的用户原文、目的地、时间和关键词。</li>
 *   <li>用低成本 Java 规则生成天气、景点、知识库或航班分支任务。</li>
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
        if (state == null) {
            TravelPlanState fallback = new TravelPlanState();
            fallback.setBranchTasks(new ArrayList<>());
            return fallback;
        }

        List<BranchTask> tasks = new ArrayList<>();
        if (hasDestinations(state)) {
            tasks.add(buildTask("knowledge-1", BranchTaskType.KNOWLEDGE, state));
        }
        if (hasDestinations(state) && hasRealtimeWeatherNeed(state)) {
            tasks.add(buildTask("weather-1", BranchTaskType.WEATHER, state));
        }
        if (hasDestinations(state) && looksLikePlacesNeed(state)) {
            tasks.add(buildTask("places-1", BranchTaskType.PLACES, state));
        }
        if (hasExplicitFlightNeed(state)) {
            tasks.add(buildTask("flight-1", BranchTaskType.FLIGHT, state));
        }

        state.setBranchTasks(tasks);
        log.info("[Graph][BranchDispatch] tasks={}, types={}",
                tasks.size(),
                tasks.stream().map(BranchTask::getType).toList());
        return state;
    }

    private static BranchTask buildTask(String taskId, BranchTaskType type, TravelPlanState state) {
        return new BranchTask(
                taskId,
                type,
                state.getUserQuery(),
                state.getDestinations(),
                state.getTravelTime(),
                state.getKeywords());
    }

    private static boolean hasDestinations(TravelPlanState state) {
        return state.getDestinations() != null && !state.getDestinations().isEmpty();
    }

    private static boolean looksLikePlacesNeed(TravelPlanState state) {
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
     * 判断是否应该调用实时天气工具。
     *
     * <p>当前 WeatherTools 接的是 OpenWeatherMap Current Weather，只能回答当前天气。
     * “国庆”“下个月”“10月”这类未来旅行时间不应触发实时天气分支，否则 Planner 可能把今天的天气误当作出行当天依据。</p>
     */
    private static boolean hasRealtimeWeatherNeed(TravelPlanState state) {
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
    private static boolean hasExplicitFlightNeed(TravelPlanState state) {
        String text = joinedText(state).toLowerCase(Locale.ROOT);
        if (containsAny(text,
                "不含机票",
                "不含国际机票",
                "不包含机票",
                "不包括机票",
                "不含航班",
                "不包含航班",
                "不包括航班",
                "机票自理",
                "国际机票自理",
                "不用查机票",
                "不查机票",
                "不需要机票",
                "不需要航班")) {
            return false;
        }
        return text.contains("机票")
                || text.contains("航班")
                || text.contains("机场")
                || text.contains("flight");
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
