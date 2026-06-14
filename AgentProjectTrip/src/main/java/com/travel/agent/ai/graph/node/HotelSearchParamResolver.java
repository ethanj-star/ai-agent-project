package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.BranchTask;
import com.travel.agent.ai.graph.model.HotelSearchRequest;

import java.time.DateTimeException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 酒店查询参数解析器（Graph 层 - 分支工具参数推导）。
 *
 * <p>系统架构位置：BranchDispatchNode -> BranchTask -> <b>HotelSearchParamResolver</b> -> BranchAgentFacade</p>
 *
 * <p>职责：
 * <ul>
 *   <li>从 {@link BranchTask} 中读取目的地、出发日期和行程天数。</li>
 *   <li>把常见中文国家或城市转换成 PlacesTools.searchHotels(...) 需要的英文城市名。</li>
 *   <li>推导入住和退房日期；参数不足时返回不可查询请求，不调用酒店 API。</li>
 * </ul>
 * </p>
 */
public final class HotelSearchParamResolver {

    private static final Pattern ISO_DATE_PATTERN = Pattern.compile("(20\\d{2})[-/](\\d{1,2})[-/](\\d{1,2})");

    private static final Pattern CHINESE_DATE_PATTERN = Pattern.compile("(20\\d{2})年(\\d{1,2})月(\\d{1,2})日?");

    /**
     * 第一版酒店分支支持的地点到英文城市映射。
     *
     * <p>国家级目的地使用代表城市，目的是给 Planner 一个真实住宿价格参考，
     * 不代表推荐用户全程只住该城市。</p>
     */
    private static final Map<String, String> HOTEL_CITY_ALIASES = Map.ofEntries(
            Map.entry("法国", "Paris"),
            Map.entry("france", "Paris"),
            Map.entry("巴黎", "Paris"),
            Map.entry("paris", "Paris"),
            Map.entry("尼斯", "Nice"),
            Map.entry("nice", "Nice"),
            Map.entry("马赛", "Marseille"),
            Map.entry("marseille", "Marseille"),
            Map.entry("里昂", "Lyon"),
            Map.entry("lyon", "Lyon"),
            Map.entry("意大利", "Rome"),
            Map.entry("italy", "Rome"),
            Map.entry("罗马", "Rome"),
            Map.entry("rome", "Rome"),
            Map.entry("米兰", "Milan"),
            Map.entry("milan", "Milan"),
            Map.entry("佛罗伦萨", "Florence"),
            Map.entry("florence", "Florence"),
            Map.entry("威尼斯", "Venice"),
            Map.entry("venice", "Venice"),
            Map.entry("都灵", "Turin"),
            Map.entry("turin", "Turin"),
            Map.entry("英国", "London"),
            Map.entry("uk", "London"),
            Map.entry("united kingdom", "London"),
            Map.entry("伦敦", "London"),
            Map.entry("london", "London"),
            Map.entry("荷兰", "Amsterdam"),
            Map.entry("netherlands", "Amsterdam"),
            Map.entry("阿姆斯特丹", "Amsterdam"),
            Map.entry("amsterdam", "Amsterdam"),
            Map.entry("爱尔兰", "Dublin"),
            Map.entry("ireland", "Dublin"),
            Map.entry("都柏林", "Dublin"),
            Map.entry("dublin", "Dublin")
    );

    private HotelSearchParamResolver() {
    }

    /**
     * 从分支任务解析酒店工具请求列表。
     *
     * <p>酒店查询通常可以按多个代表城市执行，但第一版需要限制数量，避免一个多国行程
     * 一次触发过多外部 API 调用。</p>
     *
     * @param task       Graph 派发的酒店分支任务
     * @param maxQueries 本次最多允许查询的城市数量
     * @return 酒店工具请求列表；如果只有一条 queryable=false 的结果，表示整体不可查询
     */
    public static List<HotelSearchRequest> resolve(BranchTask task, int maxQueries) {
        if (task == null) {
            return List.of(HotelSearchRequest.missing("酒店查询缺少分支任务。"));
        }

        LocalDate checkIn = firstDate(task);
        if (checkIn == null) {
            return List.of(HotelSearchRequest.missing("酒店查询缺少明确入住日期，请补充 YYYY-MM-DD 格式日期。"));
        }
        if (task.getDurationDays() == null || task.getDurationDays() <= 0) {
            return List.of(HotelSearchRequest.missing("酒店查询缺少有效行程天数，无法推导退房日期。"));
        }

        LocalDate checkOut = checkIn.plusDays(task.getDurationDays());
        List<String> cities = resolveHotelCities(task, Math.max(1, maxQueries));
        if (cities.isEmpty()) {
            return List.of(HotelSearchRequest.missing("酒店查询缺少可识别的英文城市，请补充具体目的地城市。"));
        }

        List<HotelSearchRequest> requests = new ArrayList<>();
        for (String city : cities) {
            requests.add(HotelSearchRequest.ready(
                    city,
                    checkIn.toString(),
                    checkOut.toString(),
                    city + "，" + checkIn + " 至 " + checkOut));
        }
        return requests;
    }

    /**
     * 将常见目的地解析成酒店工具可用的英文城市名。
     *
     * @param place 用户输入或结构化字段中的目的地
     * @return 英文城市名；无法确定时返回 null
     */
    public static String resolveHotelCity(String place) {
        if (!hasText(place)) {
            return null;
        }
        String trimmed = place.trim();
        String normalized = trimmed.toLowerCase(Locale.ROOT);
        String exact = HOTEL_CITY_ALIASES.get(normalized);
        if (hasText(exact)) {
            return exact;
        }
        String exactOriginal = HOTEL_CITY_ALIASES.get(trimmed);
        if (hasText(exactOriginal)) {
            return exactOriginal;
        }
        for (Map.Entry<String, String> entry : HOTEL_CITY_ALIASES.entrySet()) {
            if (normalized.contains(entry.getKey().toLowerCase(Locale.ROOT))) {
                return entry.getValue();
            }
        }
        return isAscii(trimmed) ? trimmed : null;
    }

    private static List<String> resolveHotelCities(BranchTask task, int maxQueries) {
        Set<String> cities = new LinkedHashSet<>();
        if (task.getDestinations() != null) {
            for (String destination : task.getDestinations()) {
                String city = resolveHotelCity(destination);
                if (hasText(city)) {
                    cities.add(city);
                }
                if (cities.size() >= maxQueries) {
                    break;
                }
            }
        }
        if (cities.isEmpty()) {
            String city = resolveHotelCity(task.getQuery());
            if (hasText(city)) {
                cities.add(city);
            }
        }
        return new ArrayList<>(cities);
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
