package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.BranchTask;
import com.travel.agent.ai.graph.model.FlightSearchRequest;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 航班查询参数解析器（Graph 层 - 分支工具参数推导）。
 *
 * <p>系统架构位置：BranchDispatchNode -> BranchTask -> <b>FlightSearchParamResolver</b> -> BranchAgentFacade</p>
 *
 * <p>职责：
 * <ul>
 *   <li>从 {@link BranchTask} 中读取出发地、目的地和日期。</li>
 *   <li>把常见中文城市、国家或英文城市名转换成 FlightTools 需要的 IATA 三字码。</li>
 *   <li>在参数不足时返回不可查询请求，并写清楚失败原因，不直接调用外部航班 API。</li>
 * </ul>
 * </p>
 */
public final class FlightSearchParamResolver {

    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("(20\\d{2})[-/](\\d{1,2})[-/](\\d{1,2})");

    private static final Pattern CHINESE_DATE_PATTERN = Pattern.compile("(20\\d{2})年(\\d{1,2})月(\\d{1,2})日?");

    private static final Pattern DEPARTURE_PATTERN =
            Pattern.compile("从\\s*([\\p{IsHan}A-Za-z\\s]{1,24}?)(?:出发|飞|去|前往)");

    /**
     * 第一版航班分支支持的地点到机场码映射。
     *
     * <p>这里故意只放高频、确定性较强的入口，避免把模糊城市错误映射到不合适机场。
     * 后续可以迁移成数据库表或专门的 AirportResolver。</p>
     */
    private static final Map<String, String> AIRPORT_ALIASES = Map.ofEntries(
            Map.entry("中国", "PVG"),
            Map.entry("北京", "PEK"),
            Map.entry("beijing", "PEK"),
            Map.entry("上海", "PVG"),
            Map.entry("shanghai", "PVG"),
            Map.entry("浦东", "PVG"),
            Map.entry("广州", "CAN"),
            Map.entry("guangzhou", "CAN"),
            Map.entry("深圳", "SZX"),
            Map.entry("shenzhen", "SZX"),
            Map.entry("香港", "HKG"),
            Map.entry("hong kong", "HKG"),
            Map.entry("成都", "TFU"),
            Map.entry("chengdu", "TFU"),
            Map.entry("杭州", "HGH"),
            Map.entry("hangzhou", "HGH"),
            Map.entry("南京", "NKG"),
            Map.entry("nanjing", "NKG"),
            Map.entry("法国", "CDG"),
            Map.entry("france", "CDG"),
            Map.entry("巴黎", "CDG"),
            Map.entry("paris", "CDG"),
            Map.entry("意大利", "FCO"),
            Map.entry("italy", "FCO"),
            Map.entry("罗马", "FCO"),
            Map.entry("rome", "FCO"),
            Map.entry("米兰", "MXP"),
            Map.entry("milan", "MXP"),
            Map.entry("英国", "LHR"),
            Map.entry("uk", "LHR"),
            Map.entry("united kingdom", "LHR"),
            Map.entry("伦敦", "LHR"),
            Map.entry("london", "LHR"),
            Map.entry("荷兰", "AMS"),
            Map.entry("netherlands", "AMS"),
            Map.entry("阿姆斯特丹", "AMS"),
            Map.entry("amsterdam", "AMS"),
            Map.entry("爱尔兰", "DUB"),
            Map.entry("ireland", "DUB"),
            Map.entry("都柏林", "DUB"),
            Map.entry("dublin", "DUB")
    );

    private FlightSearchParamResolver() {
    }

    /**
     * 从分支任务解析航班工具请求。
     *
     * <p>解析只做确定性推导：能得到出发机场、到达机场和明确日期时返回可查询请求；
     * 任一关键参数缺失时返回失败请求，让 BranchAgentFacade 生成降级结果。</p>
     *
     * @param task Graph 派发的航班分支任务
     * @return 航班工具请求；queryable=false 时不得调用 FlightTools
     */
    public static FlightSearchRequest resolve(BranchTask task) {
        if (task == null) {
            return FlightSearchRequest.missing("航班查询缺少分支任务。");
        }

        String originPlace = firstText(task.getDepartureCity(), extractDeparturePlace(task.getQuery()));
        String originCode = resolveAirportCode(originPlace);
        if (!hasText(originCode)) {
            return FlightSearchRequest.missing("航班查询缺少可识别的出发地，请补充出发城市。");
        }

        String destinationPlace = firstDestination(task);
        String destinationCode = resolveAirportCode(destinationPlace);
        if (!hasText(destinationCode)) {
            return FlightSearchRequest.missing("航班查询缺少可识别的目的地机场，请补充具体目的地城市。");
        }

        LocalDate departureDate = firstDate(task);
        if (departureDate == null) {
            return FlightSearchRequest.missing("航班查询缺少明确出发日期，请补充 YYYY-MM-DD 格式日期。");
        }

        return FlightSearchRequest.ready(
                originCode,
                destinationCode,
                departureDate.toString(),
                defaultText(originPlace, originCode) + "(" + originCode + ") -> "
                        + defaultText(destinationPlace, destinationCode) + "(" + destinationCode + ")，"
                        + departureDate);
    }

    /**
     * 将常见城市、国家或机场码解析成 IATA 三字码。
     *
     * @param place 用户输入或结构化字段中的地点
     * @return IATA 三字码；无法确定时返回 null
     */
    public static String resolveAirportCode(String place) {
        if (!hasText(place)) {
            return null;
        }
        String trimmed = place.trim();
        if (trimmed.matches("[A-Za-z]{3}")) {
            return trimmed.toUpperCase(Locale.ROOT);
        }
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        String exact = AIRPORT_ALIASES.get(normalized);
        if (hasText(exact)) {
            return exact;
        }
        String exactOriginal = AIRPORT_ALIASES.get(trimmed);
        if (hasText(exactOriginal)) {
            return exactOriginal;
        }
        for (Map.Entry<String, String> entry : AIRPORT_ALIASES.entrySet()) {
            if (normalized.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }
        return null;
    }

    private static String firstDestination(BranchTask task) {
        if (task.getDestinations() == null || task.getDestinations().isEmpty()) {
            return null;
        }
        for (String destination : task.getDestinations()) {
            if (hasText(destination)) {
                return destination.trim();
            }
        }
        return null;
    }

    private static LocalDate firstDate(BranchTask task) {
        if (task.getStartDate() != null) {
            return task.getStartDate();
        }
        LocalDate fromTravelTime = parseDate(task.getTravelTime());
        if (fromTravelTime != null) {
            return fromTravelTime;
        }
        return parseDate(task.getQuery());
    }

    private static LocalDate parseDate(String text) {
        if (!hasText(text)) {
            return null;
        }
        LocalDate iso = parseDateWithPattern(ISO_DATE_PATTERN, text);
        if (iso != null) {
            return iso;
        }
        return parseDateWithPattern(CHINESE_DATE_PATTERN, text);
    }

    private static LocalDate parseDateWithPattern(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        if (!matcher.find()) {
            return null;
        }
        try {
            int year = Integer.parseInt(matcher.group(1));
            int month = Integer.parseInt(matcher.group(2));
            int day = Integer.parseInt(matcher.group(3));
            return LocalDate.of(year, month, day);
        } catch (DateTimeException | NumberFormatException e) {
            return null;
        }
    }

    private static String extractDeparturePlace(String query) {
        if (!hasText(query)) {
            return null;
        }
        Matcher matcher = DEPARTURE_PATTERN.matcher(query);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String firstText(String first, String second) {
        return hasText(first) ? first.trim() : second;
    }

    private static String defaultText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
