package com.travel.agent.ai.agents;

import com.travel.agent.ai.graph.model.BranchResult;
import com.travel.agent.ai.graph.model.BranchTask;
import com.travel.agent.ai.graph.model.BranchTaskType;
import com.travel.agent.ai.graph.model.FlightSearchRequest;
import com.travel.agent.ai.graph.model.HotelSearchRequest;
import com.travel.agent.ai.graph.node.FlightSearchParamResolver;
import com.travel.agent.ai.graph.node.HotelSearchParamResolver;
import com.travel.agent.ai.tools.FlightTools;
import com.travel.agent.ai.tools.KnowledgeTools;
import com.travel.agent.ai.tools.PlacesTools;
import com.travel.agent.ai.tools.WeatherTools;
import com.travel.agent.core.dto.AttractionDTO;
import com.travel.agent.core.dto.FlightDTO;
import com.travel.agent.core.dto.HotelDTO;
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
 * <p>第十二阶段开始，航班和酒店分支会真实调用外部工具；参数缺失或 API 失败时，
 * 仍返回失败型 BranchResult，提醒 Planner 不要伪造实时价格、库存或可售状态。</p>
 */
@Service
public class BranchAgentFacade {

    private static final Logger log = LoggerFactory.getLogger(BranchAgentFacade.class);

    /** 单个分支最多查询的代表城市数量，避免一次请求触发过多外部 API 调用。 */
    private static final int MAX_TOOL_CITY_QUERIES = 3;

    /** 酒店分支最多查询的代表城市数量，住宿价格只做预算参考，不需要覆盖所有停留点。 */
    private static final int MAX_HOTEL_CITY_QUERIES = 2;

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

    private final FlightTools flightTools;
    private final WeatherTools weatherTools;
    private final PlacesTools placesTools;
    private final KnowledgeTools knowledgeTools;

    /**
     * 构造器注入当前已经存在的工具桥接器。
     *
     * @param flightTools    航班工具桥接器，负责真实航班查询
     * @param weatherTools   天气工具桥接器
     * @param placesTools    景点/酒店工具桥接器
     * @param knowledgeTools 私有知识库检索工具桥接器
     */
    public BranchAgentFacade(FlightTools flightTools,
                             WeatherTools weatherTools,
                             PlacesTools placesTools,
                             KnowledgeTools knowledgeTools) {
        this.flightTools = flightTools;
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
        // 分支任务是 Graph 派发出来的，如果连类型都没有，就没有办法判断该调用哪个工具。
        // 这里返回失败结果而不是抛异常，是为了让 Planner 仍能继续生成“缺少实时数据”的保守方案。
        if (task == null || task.getType() == null) {
            return BranchResult.failure(task, "分支任务为空或缺少类型，已跳过。", "BranchTask is null or type is null");
        }

        try {
            // BranchTaskType 是分支执行的唯一机器可读路由依据，避免依赖自然语言摘要猜测工具类型。
            return switch (task.getType()) {
                case WEATHER -> executeWeather(task);
                case PLACES -> executePlaces(task);
                case HOTEL -> executeHotel(task);
                case KNOWLEDGE -> executeKnowledge(task);
                case FLIGHT -> executeFlight(task);
            };
        } catch (Exception e) {
            // 单个工具失败不应该打断完整行程规划；失败会被包装成 BranchResult 交给 Planner 显式说明风险。
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
        // WeatherTools 接收英文城市名；先把中文国家/城市规整成代表城市，避免外部 API 参数不可识别。
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
            // WeatherService 自己会返回兜底 DTO。这里要识别这种兜底结果，避免 Planner 误当真实天气使用。
            if (weather == null || WEATHER_FAILURE_DESCRIPTION.equals(weather.description())) {
                failures.add(target);
                continue;
            }
            summaries.add(weather.cityName() + " 当前 " + weather.temperature()
                    + "°C，" + weather.description() + "，湿度 " + weather.humidity() + "%");
            rawData.add(weather.toString());
        }

        if (summaries.isEmpty()) {
            // 所有城市都失败时，不向上抛异常；让最终答案提示“只能参考季节性建议”更稳妥。
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
        // 景点工具同样依赖英文城市名；无法解析城市时直接降级，避免调用外部服务产生噪声。
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
            // 单个城市查不到景点不代表整个分支失败，继续尝试其他代表城市。
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
     * 执行航班分支。
     *
     * <p>处理流程：
     * <ol>
     *   <li>使用 FlightSearchParamResolver 把任务中的出发地、目的地和日期转成工具参数。</li>
     *   <li>参数完整时调用 FlightTools.searchFlights(...)。</li>
     *   <li>把最多三条航班候选压缩为 Planner 可读摘要。</li>
     *   <li>参数缺失、工具未配置或 API 空结果时返回失败 BranchResult，不伪造航班价格。</li>
     * </ol>
     * </p>
     */
    private BranchResult executeFlight(BranchTask task) {
        if (flightTools == null) {
            return BranchResult.failure(task,
                    "航班分支未配置真实航班工具，Planner 不应生成实时航班号、票价或可售状态。",
                    "FlightTools bean is missing");
        }

        FlightSearchRequest request = FlightSearchParamResolver.resolve(task);
        if (!request.queryable()) {
            return BranchResult.failure(task,
                    "航班分支未获得可用实时结果：" + request.missingReason()
                            + " Planner 不应编造航班号、实时票价或可售状态。",
                    request.missingReason());
        }

        List<FlightDTO> flights = flightTools.searchFlights(
                request.originCode(),
                request.destinationCode(),
                request.departureDate());
        if (flights == null || flights.isEmpty()) {
            return BranchResult.failure(task,
                    "航班分支没有查到可用航班结果，Planner 只能给常规抵离建议，不应编造实时票价。",
                    "empty flights for " + request.sourceDescription());
        }

        String summary = "航班参考（" + request.sourceDescription() + "）："
                + flights.stream()
                .limit(3)
                .map(BranchAgentFacade::formatFlight)
                .collect(Collectors.joining("；"));
        if (Boolean.FALSE.equals(task.getBudgetIncludesInternationalFlight())) {
            summary += "。用户已说明预算不含国际机票，Planner 只能把该航班信息作为抵离路线参考，不应计入旅行预算。";
        }
        return BranchResult.success(task, summary, flights.toString());
    }

    /**
     * 执行酒店分支。
     *
     * <p>处理流程：
     * <ol>
     *   <li>使用 HotelSearchParamResolver 推导城市、入住日期和退房日期。</li>
     *   <li>最多查询两个代表城市，控制外部 API 调用次数。</li>
     *   <li>把酒店名称、每晚价格和评分压缩为 Planner 可读摘要。</li>
     *   <li>参数缺失或 API 空结果时返回失败 BranchResult，不伪造酒店价格或库存。</li>
     * </ol>
     * </p>
     */
    private BranchResult executeHotel(BranchTask task) {
        List<HotelSearchRequest> requests = HotelSearchParamResolver.resolve(task, MAX_HOTEL_CITY_QUERIES);
        if (requests.isEmpty() || requests.stream().noneMatch(HotelSearchRequest::queryable)) {
            String reason = requests.isEmpty() ? "酒店查询缺少可用参数。" : requests.get(0).missingReason();
            return BranchResult.failure(task,
                    "酒店分支未获得可用实时结果：" + reason
                            + " Planner 不应编造酒店价格、评分或库存状态。",
                    reason);
        }

        List<String> summaries = new ArrayList<>();
        List<String> rawData = new ArrayList<>();
        List<String> failures = new ArrayList<>();
        for (HotelSearchRequest request : requests) {
            if (!request.queryable()) {
                failures.add(request.missingReason());
                continue;
            }
            List<HotelDTO> hotels = placesTools.searchHotels(
                    request.city(),
                    request.checkInDate(),
                    request.checkOutDate());
            if (hotels == null || hotels.isEmpty()) {
                failures.add(request.city());
                continue;
            }
            summaries.add(request.sourceDescription() + "："
                    + hotels.stream()
                    .limit(3)
                    .map(BranchAgentFacade::formatHotel)
                    .collect(Collectors.joining("；")));
            rawData.add(request.city() + "=" + hotels);
        }

        if (summaries.isEmpty()) {
            return BranchResult.failure(task,
                    "酒店分支没有查到可用酒店结果，Planner 只能给常规住宿建议，不应编造实时价格或库存。",
                    "empty hotels for " + failures);
        }

        String summary = "酒店参考（按代表城市查询）：" + String.join("；", summaries);
        if (!failures.isEmpty()) {
            summary += "。部分酒店查询暂不可用：" + String.join("、", failures) + "。";
        }
        return BranchResult.success(task, summary, String.join("\n", rawData));
    }

    /**
     * 执行知识库分支。
     */
    private BranchResult executeKnowledge(BranchTask task) {
        // 知识库检索优先使用原始用户问题；没有原问题时才退回第一个目的地。
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
                    // 多国行程只取前几个代表城市，控制外部 API 调用次数和响应时间。
                    break;
                }
            }
        }

        if (cities.isEmpty()) {
            // 有些任务没有结构化目的地，但原始 query 里可能包含“巴黎天气”这类可识别文本。
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

        // 第一层：精确匹配英文小写 key，例如 france -> Paris。
        String exact = TOOL_CITY_ALIASES.get(normalized);
        if (hasText(exact)) {
            return exact;
        }

        // 第二层：精确匹配原文 key，例如 “法国” -> Paris。
        String exactOriginal = TOOL_CITY_ALIASES.get(trimmed);
        if (hasText(exactOriginal)) {
            return exactOriginal;
        }

        // 第三层：包含匹配，处理“法国巴黎”“瑞士苏黎世附近”这类混合短语。
        for (Map.Entry<String, String> entry : TOOL_CITY_ALIASES.entrySet()) {
            if (normalized.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }

        // 未收录中文地点无法保证外部工具能识别；英文输入则假定用户已经给了可查询城市名。
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

    private static String formatFlight(FlightDTO flight) {
        if (flight == null) {
            return "未知航班";
        }
        return defaultText(flight.airline(), "未知航司")
                + " " + flight.origin() + "->" + flight.destination()
                + " " + formatEuroPrice(flight.priceEuros());
    }

    private static String formatHotel(HotelDTO hotel) {
        if (hotel == null) {
            return "未知酒店";
        }
        return defaultText(hotel.name(), "未知酒店")
                + "(" + defaultText(hotel.price(), "价格未知")
                + ", " + hotel.rating() + "分)";
    }

    private static String formatEuroPrice(double price) {
        if (price <= 0) {
            return "价格未知";
        }
        if (price == Math.rint(price)) {
            return "约" + (long) price + "EUR";
        }
        return "约" + String.format(Locale.ROOT, "%.2fEUR", price);
    }

    private static String defaultText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }
}
