package com.travel.agent.ai.agents;

import com.travel.agent.ai.graph.model.BranchResult;
import com.travel.agent.ai.graph.model.BranchTask;
import com.travel.agent.ai.graph.model.BranchTaskType;
import com.travel.agent.ai.tools.KnowledgeTools;
import com.travel.agent.ai.tools.PlacesTools;
import com.travel.agent.ai.tools.WeatherTools;
import com.travel.agent.core.dto.AttractionDTO;
import com.travel.agent.core.dto.WeatherDTO;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 分支 Agent 执行门面。
 *
 * <p>系统架构位置：BranchExecuteNode -> <b>BranchAgentFacade</b> -> Tools / Service</p>
 *
 * <p>职责：
 * <ul>
 *   <li>接收 Graph 生成的 {@link BranchTask}，根据任务类型调用对应工具能力。</li>
 *   <li>把中文国家或城市规范化为工具可识别的英文城市名，例如“法国”转为 “Paris”。</li>
 *   <li>把工具返回值统一包装为 {@link BranchResult}，供 Planner 注入上下文。</li>
 *   <li>捕获所有分支异常并返回失败结果，避免单个工具故障打断整个规划流程。</li>
 * </ul>
 * </p>
 *
 * <p>第三阶段第一版采用顺序、轻量的执行方式；航班分支暂时只返回降级结果，
 * 等后续具备机场代码和日期解析能力后再接入真实航班查询。</p>
 */
@Service
public class BranchAgentFacade {

    private static final Logger log = LoggerFactory.getLogger(BranchAgentFacade.class);

    /** 单个分支最多查询的代表城市数量，避免一次请求触发过多外部 API 调用。 */
    private static final int MAX_TOOL_CITY_QUERIES = 3;

    /** 天气查询失败时 WeatherService 返回的描述，用于判断该结果不可作为实时天气引用。 */
    private static final String WEATHER_FAILURE_DESCRIPTION = "获取天气失败";

    /**
     * 工具城市名映射表。
     *
     * <p>天气和景点工具目前都更适合接收英文城市名；Gatekeeper 却经常抽取中文国家名。
     * 这里先用确定性映射做第一层参数规范化，避免把“法国”直接传给 OpenWeatherMap 造成 404。</p>
     */
    private static final Map<String, String> TOOL_CITY_ALIASES = Map.ofEntries(
            Map.entry("法国", "Paris"),
            Map.entry("france", "Paris"),
            Map.entry("意大利", "Rome"),
            Map.entry("italy", "Rome"),
            Map.entry("瑞士", "Zurich"),
            Map.entry("switzerland", "Zurich"),
            Map.entry("德国", "Berlin"),
            Map.entry("germany", "Berlin"),
            Map.entry("英国", "London"),
            Map.entry("uk", "London"),
            Map.entry("united kingdom", "London"),
            Map.entry("西班牙", "Madrid"),
            Map.entry("spain", "Madrid"),
            Map.entry("葡萄牙", "Lisbon"),
            Map.entry("portugal", "Lisbon"),
            Map.entry("荷兰", "Amsterdam"),
            Map.entry("netherlands", "Amsterdam"),
            Map.entry("比利时", "Brussels"),
            Map.entry("belgium", "Brussels"),
            Map.entry("奥地利", "Vienna"),
            Map.entry("austria", "Vienna"),
            Map.entry("捷克", "Prague"),
            Map.entry("czech", "Prague"),
            Map.entry("希腊", "Athens"),
            Map.entry("greece", "Athens"),
            Map.entry("爱尔兰", "Dublin"),
            Map.entry("ireland", "Dublin"),
            Map.entry("冰岛", "Reykjavik"),
            Map.entry("iceland", "Reykjavik"),
            Map.entry("挪威", "Oslo"),
            Map.entry("norway", "Oslo"),
            Map.entry("瑞典", "Stockholm"),
            Map.entry("sweden", "Stockholm"),
            Map.entry("丹麦", "Copenhagen"),
            Map.entry("denmark", "Copenhagen"),
            Map.entry("芬兰", "Helsinki"),
            Map.entry("finland", "Helsinki"),
            Map.entry("巴黎", "Paris"),
            Map.entry("罗马", "Rome"),
            Map.entry("佛罗伦萨", "Florence"),
            Map.entry("威尼斯", "Venice"),
            Map.entry("米兰", "Milan"),
            Map.entry("尼斯", "Nice"),
            Map.entry("马赛", "Marseille"),
            Map.entry("里昂", "Lyon"),
            Map.entry("安纳西", "Annecy"),
            Map.entry("霞慕尼", "Chamonix"),
            Map.entry("都灵", "Turin"),
            Map.entry("那不勒斯", "Naples"),
            Map.entry("博洛尼亚", "Bologna"),
            Map.entry("维罗纳", "Verona"),
            Map.entry("卢卡", "Lucca"),
            Map.entry("比萨", "Pisa"),
            Map.entry("阿西西", "Assisi"),
            Map.entry("伦敦", "London"),
            Map.entry("巴塞罗那", "Barcelona"),
            Map.entry("马德里", "Madrid"),
            Map.entry("里斯本", "Lisbon"),
            Map.entry("波尔图", "Porto"),
            Map.entry("阿姆斯特丹", "Amsterdam"),
            Map.entry("布鲁塞尔", "Brussels"),
            Map.entry("布拉格", "Prague"),
            Map.entry("维也纳", "Vienna"),
            Map.entry("苏黎世", "Zurich"),
            Map.entry("卢塞恩", "Lucerne"),
            Map.entry("日内瓦", "Geneva"),
            Map.entry("慕尼黑", "Munich"),
            Map.entry("柏林", "Berlin")
    );

    private final WeatherTools weatherTools;
    private final PlacesTools placesTools;
    private final KnowledgeTools knowledgeTools;

    /**
     * 构造器注入当前已经存在的工具桥接器。
     *
     * @param weatherTools   天气工具桥接器
     * @param placesTools    景点/地点工具桥接器
     * @param knowledgeTools 私有知识库检索工具桥接器
     */
    public BranchAgentFacade(WeatherTools weatherTools,
                             PlacesTools placesTools,
                             KnowledgeTools knowledgeTools) {
        this.weatherTools = weatherTools;
        this.placesTools = placesTools;
        this.knowledgeTools = knowledgeTools;
    }

    /**
     * 执行单个分支任务。
     *
     * <p>处理流程：</p>
     * <ol>
     *   <li>根据 {@link BranchTaskType} 选择工具能力。</li>
     *   <li>将工具返回值压缩为短摘要和原始数据。</li>
     *   <li>发生异常时返回失败结果，不继续向上抛出。</li>
     * </ol>
     *
     * @param task 分支任务
     * @return 分支执行结果；失败时包含降级摘要和错误信息
     */
    public BranchResult execute(BranchTask task) {
        if (task == null || task.getType() == null) {
            return BranchResult.failure(task, "分支任务为空或缺少类型，已跳过。", "BranchTask is null or type is null");
        }

        try {
            return switch (task.getType()) {
                case WEATHER -> executeWeather(task);
                case PLACES -> executePlaces(task);
                case KNOWLEDGE -> executeKnowledge(task);
                case FLIGHT -> executeFlightFallback(task);
            };
        } catch (Exception e) {
            log.warn("[BranchAgent] task failed: type={}, error={}", task.getType(), e.getMessage());
            return BranchResult.failure(task,
                    task.getType() + " 分支暂时不可用，Planner 不应伪造该类实时数据。",
                    e.getMessage());
        }
    }

    /**
     * 执行天气分支。
     */
    private BranchResult executeWeather(BranchTask task) {
        List<String> targets = resolveToolCities(task);
        if (targets.isEmpty()) {
            return BranchResult.failure(task,
                    "天气分支缺少可查询英文城市，已跳过实时天气查询。",
                    "missing resolvable city");
        }

        List<String> summaries = new ArrayList<>();
        List<String> rawData = new ArrayList<>();
        List<String> failures = new ArrayList<>();

        for (String target : targets) {
            WeatherDTO weather = weatherTools.getWeather(target);
            if (weather == null || WEATHER_FAILURE_DESCRIPTION.equals(weather.description())) {
                failures.add(target);
                continue;
            }
            summaries.add(weather.cityName() + " 当前 " + weather.temperature()
                    + "°C，" + weather.description() + "，湿度 " + weather.humidity() + "%");
            rawData.add(weather.toString());
        }

        if (summaries.isEmpty()) {
            return BranchResult.failure(task,
                    "天气分支未获得可用实时天气，Planner 只能给季节性天气建议。",
                    "weather unavailable for " + String.join(", ", targets));
        }

        String summary = "天气参考（按代表城市查询）：" + String.join("；", summaries) + "。";
        if (!failures.isEmpty()) {
            summary += " 部分城市天气暂不可用：" + String.join("、", failures) + "。";
        }
        return BranchResult.success(task, summary, String.join("\n", rawData));
    }

    /**
     * 执行景点分支。
     */
    private BranchResult executePlaces(BranchTask task) {
        List<String> targets = resolveToolCities(task);
        if (targets.isEmpty()) {
            return BranchResult.failure(task,
                    "景点分支缺少可查询英文城市，已跳过景点查询。",
                    "missing resolvable city");
        }

        List<String> summaries = new ArrayList<>();
        List<String> rawData = new ArrayList<>();
        for (String target : targets) {
            List<AttractionDTO> attractions = placesTools.searchAttractions(target);
            if (attractions == null || attractions.isEmpty()) {
                continue;
            }
            summaries.add(target + "：" + attractions.stream()
                    .map(item -> item.name() + "(" + item.description() + ", " + item.rating() + "分)")
                    .collect(Collectors.joining("；")));
            rawData.add(target + "=" + attractions);
        }

        if (summaries.isEmpty()) {
            return BranchResult.failure(task, "景点分支没有查到可用结果，Planner 应以 RAG 和常识方案为主。", "empty attractions");
        }

        String summary = "景点参考（按代表城市查询）：" + String.join("；", summaries);
        return BranchResult.success(task, summary, String.join("\n", rawData));
    }

    /**
     * 执行知识库分支。
     */
    private BranchResult executeKnowledge(BranchTask task) {
        String query = hasText(task.getQuery()) ? task.getQuery() : firstDestinationOrQuery(task);
        if (!hasText(query)) {
            return BranchResult.failure(task, "知识分支缺少查询文本，已跳过。", "missing query");
        }

        String guide = knowledgeTools.searchTravelGuide(query);
        if (!hasText(guide)) {
            return BranchResult.failure(task, "知识分支没有返回可用内容。", "empty knowledge result");
        }
        return BranchResult.success(task, "知识库参考已检索，可用于防坑、交通和 POI 细化。", guide);
    }

    /**
     * 航班分支第一版降级处理。
     */
    private static BranchResult executeFlightFallback(BranchTask task) {
        return BranchResult.failure(task,
                "航班分支第一版暂未启用真实查询；Planner 不应生成具体航班号、实时票价或可售状态。",
                "flight branch is not enabled in phase 3 slice 1");
    }

    private static String firstDestinationOrQuery(BranchTask task) {
        if (task != null && task.getDestinations() != null && !task.getDestinations().isEmpty()) {
            return task.getDestinations().get(0);
        }
        return task == null ? null : task.getQuery();
    }

    /**
     * 从分支任务中解析工具可用的英文城市名。
     *
     * <p>优先读取 destinations；如果没有目的地，再从 query 中尝试识别。返回值去重并限制数量，
     * 避免一次多国行程触发过多外部 API 请求。</p>
     */
    private static List<String> resolveToolCities(BranchTask task) {
        Set<String> cities = new LinkedHashSet<>();
        if (task != null && task.getDestinations() != null) {
            for (String destination : task.getDestinations()) {
                String city = normalizeToolCity(destination);
                if (hasText(city)) {
                    cities.add(city);
                }
                if (cities.size() >= MAX_TOOL_CITY_QUERIES) {
                    break;
                }
            }
        }

        if (cities.isEmpty()) {
            String city = normalizeToolCity(task == null ? null : task.getQuery());
            if (hasText(city)) {
                cities.add(city);
            }
        }
        return new ArrayList<>(cities);
    }

    /**
     * 将中文国家、中文城市或英文国家名转为英文城市名。
     *
     * <p>如果输入本身是英文城市名，则直接返回；如果输入是未收录的中文地点，则返回 null，
     * 让分支以失败结果降级，而不是把不合法参数传给外部 API。</p>
     */
    private static String normalizeToolCity(String value) {
        if (!hasText(value)) {
            return null;
        }
        String trimmed = value.trim();
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        String exact = TOOL_CITY_ALIASES.get(normalized);
        if (hasText(exact)) {
            return exact;
        }
        String exactOriginal = TOOL_CITY_ALIASES.get(trimmed);
        if (hasText(exactOriginal)) {
            return exactOriginal;
        }
        for (Map.Entry<String, String> entry : TOOL_CITY_ALIASES.entrySet()) {
            if (normalized.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }
        return isAscii(trimmed) ? trimmed : null;
    }

    private static boolean isAscii(String value) {
        if (!hasText(value)) {
            return false;
        }
        for (int i = 0; i < value.length(); i++) {
            if (value.charAt(i) > 127) {
                return false;
            }
        }
        return true;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
