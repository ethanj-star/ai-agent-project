package com.travel.agent.ai.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.ai.AiModelBeanNames;
import com.travel.agent.ai.graph.model.RequirementStatus;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 旅行需求抽取智能体（AI 层 - 第五阶段轻量填表入口）。
 *
 * <p>系统架构位置：RequirementController -> <b>RequirementExtractionAgent</b> -> TravelRequirementSpec</p>
 *
 * <p>职责：
 * <ul>
 *   <li>使用低成本 Gatekeeper 模型把用户自然语言抽取为结构化旅行需求表。</li>
 *   <li>只做字段抽取，不生成完整行程，避免在信息不完整时浪费核心模型 token。</li>
 *   <li>模型失败或 JSON 解析失败时，降级到确定性规则抽取，保证前端仍能得到可编辑表单。</li>
 *   <li>对预算、时长、机票边界等关键字段做规则补强，减少模型漏提导致的无效规划。</li>
 * </ul>
 * </p>
 */
@Service
public class RequirementExtractionAgent {

    private static final Logger log = LoggerFactory.getLogger(RequirementExtractionAgent.class);

    private static final Pattern ARABIC_DAY_PATTERN =
            Pattern.compile("(\\d{1,2})\\s*(?:天|日)\\s*(?:左右|上下)?");
    private static final Pattern NIGHT_DAY_PATTERN =
            Pattern.compile("(\\d{1,2})\\s*晚\\s*(\\d{1,2})\\s*(?:天|日)");
    private static final Pattern ARABIC_WEEK_PATTERN =
            Pattern.compile("(\\d{1,2})\\s*(?:周|星期)\\s*(?:左右|上下)?");
    private static final Pattern CHINESE_DAY_PATTERN =
            Pattern.compile("([一二两三四五六七八九十]{1,3})\\s*(?:天|日)\\s*(?:左右|上下)?");
    private static final Pattern CHINESE_WEEK_PATTERN =
            Pattern.compile("([一二两三四五六七八九十]{1,3})\\s*(?:周|星期)\\s*(?:左右|上下)?");
    private static final Pattern BUDGET_PATTERN =
            Pattern.compile("(?:预算|花费|价格|控制在|不超过|大概|约)?\\s*(\\d{3,7})(?:\\s*)(欧元|欧|eur|€|人民币|元|cny|美元|美金|usd|英镑|镑|gbp)?",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern TRAVELER_PATTERN =
            Pattern.compile("(\\d{1,2})\\s*(?:个)?人");
    private static final Pattern DEPARTURE_PATTERN =
            Pattern.compile("从\\s*([\\u4e00-\\u9fa5A-Za-z]{2,20})\\s*(?:出发|起飞|走|过去)");

    /**
     * 需求抽取系统提示词。
     *
     * <p>提示词把模型角色锁定为“填表器”，禁止输出行程方案，降低第五阶段入口误触发复杂生成的概率。</p>
     */
    private static final String SYSTEM_PROMPT = """
            你是欧洲旅行系统的旅行需求抽取器，不是行程规划师。
            你的唯一任务是从用户自然语言中抽取结构化旅行需求表。

            严格要求：
            1. 只输出合法 JSON Object，不要输出 Markdown 代码块，不要解释。
            2. 只抽取用户明确说出的信息，不要脑补。
            3. 不确定的字段填 null 或空数组。
            4. 不能生成完整旅行方案。
            5. budgetCurrency 使用 ISO 风格：CNY / EUR / USD / GBP。
            6. budgetIncludesInternationalFlight 只有用户明确说包含或不包含国际机票时才填 true/false。

            输出 JSON Schema：
            {
              "destinations": ["string"],
              "departureCity": "string or null",
              "startDateText": "string or null",
              "startDate": "yyyy-MM-dd or null",
              "durationDays": 10,
              "travelerCount": 2,
              "budgetAmount": 1200,
              "budgetCurrency": "EUR",
              "budgetIncludesInternationalFlight": false,
              "preferences": ["string"],
              "avoidances": ["string"],
              "travelStyle": "string or null",
              "accommodationPreference": "string or null",
              "transportPreference": "string or null"
            }
            """;

    /** 低成本 Gatekeeper 模型客户端，用于第五阶段需求抽取。 */
    private final ChatClient chatClient;

    /** Jackson 解析器，用于把模型 JSON 映射为 TravelRequirementSpec。 */
    private final ObjectMapper objectMapper;

    /**
     * 构造器注入 Gatekeeper 模型和 Jackson。
     *
     * <p>需求抽取属于低成本、短 JSON 任务，优先复用 DeepSeek Flash；
     * 不使用核心规划模型，避免用户尚未确认需求表前产生高额 token 消耗。</p>
     *
     * @param chatModel    Gatekeeper ChatModel
     * @param objectMapper JSON 解析器
     */
    @Autowired
    public RequirementExtractionAgent(@Qualifier(AiModelBeanNames.GATEKEEPER_CHAT_MODEL) ChatModel chatModel,
                                      ObjectMapper objectMapper) {
        this(ChatClient.create(chatModel), objectMapper);
    }

    /**
     * 包内测试构造器。
     *
     * @param chatClient   可为空或 mock 的 ChatClient
     * @param objectMapper JSON 解析器
     */
    RequirementExtractionAgent(ChatClient chatClient, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 从自然语言中抽取结构化旅行需求表。
     *
     * <p>处理流程：
     * <ol>
     *   <li>先用确定性规则抽取时长、预算、机票边界、常见国家和偏好作为兜底。</li>
     *   <li>调用低成本模型抽取更灵活的字段。</li>
     *   <li>解析模型 JSON；失败时使用规则结果。</li>
     *   <li>用规则结果补齐模型遗漏的确定性字段，并初始化 requirementId / sessionId / status。</li>
     * </ol>
     * </p>
     *
     * <p>异常策略：模型调用或解析失败不会中断请求，前端仍会得到一张可编辑需求表。</p>
     *
     * @param sessionId 当前会话 ID，可为空
     * @param message   用户自然语言旅行需求
     * @return 结构化需求表草稿
     */
    public TravelRequirementSpec extract(String sessionId, String message) {
        TravelRequirementSpec fallback = extractByRules(sessionId, message);
        TravelRequirementSpec extracted = fallback;

        if (chatClient != null && hasText(message)) {
            try {
                String raw = callModel(message);
                extracted = parseOrFallback(raw, fallback);
            } catch (Exception e) {
                log.warn("[RequirementExtraction] model call failed, use rule fallback: {}", e.getMessage());
            }
        }

        mergeMissingFields(extracted, fallback);
        normalize(extracted, sessionId, message);
        return extracted;
    }

    /**
     * 调用低成本模型执行需求抽取。
     *
     * @param message 用户自然语言需求
     * @return 模型原始 JSON 文本
     */
    protected String callModel(String message) {
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(message)
                .call()
                .content();
    }

    /**
     * 从模型输出解析需求表，失败时返回规则兜底。
     */
    TravelRequirementSpec parseOrFallback(String rawResponse, TravelRequirementSpec fallback) {
        String cleaned = stripMarkdownFences(rawResponse);
        if (!hasText(cleaned)) {
            return fallback;
        }
        try {
            return objectMapper.readValue(cleaned, TravelRequirementSpec.class);
        } catch (Exception first) {
            String json = extractJsonObject(cleaned);
            if (hasText(json)) {
                try {
                    return objectMapper.readValue(json, TravelRequirementSpec.class);
                } catch (Exception ignored) {
                    log.warn("[RequirementExtraction] extracted JSON parse failed: {}", ignored.getMessage());
                }
            }
            return fallback;
        }
    }

    /**
     * 规则兜底抽取。
     *
     * <p>本方法覆盖最关键且确定性强的字段：常见欧洲国家、行程天数、预算、人数、出发城市、机票边界和偏好。
     * 它不是要取代模型，而是保证模型失败时系统仍能进入“可编辑表单”状态。</p>
     */
    static TravelRequirementSpec extractByRules(String sessionId, String message) {
        TravelRequirementSpec spec = new TravelRequirementSpec();
        spec.setSessionId(sessionId);
        spec.setOriginalMessage(message);
        spec.setDestinations(extractDestinations(message));
        spec.setDurationDays(extractDurationDays(message));
        spec.setTravelerCount(extractTravelerCount(message));
        spec.setDepartureCity(extractDepartureCity(message));
        spec.setStartDateText(extractStartDateText(message));
        spec.setBudgetAmount(extractBudgetAmount(message));
        spec.setBudgetCurrency(extractBudgetCurrency(message));
        spec.setBudgetIncludesInternationalFlight(extractFlightBudgetBoundary(message));
        spec.setPreferences(extractPreferences(message));
        spec.setAvoidances(extractAvoidances(message));
        spec.setTravelStyle(extractTravelStyle(message));
        spec.setAccommodationPreference(extractAccommodationPreference(message));
        spec.setTransportPreference(extractTransportPreference(message));
        return spec;
    }

    private static List<String> extractDestinations(String message) {
        Set<String> destinations = new LinkedHashSet<>();
        if (!hasText(message)) {
            return new ArrayList<>();
        }
        addIfContains(destinations, message, "法国", "法国", "法兰西", "巴黎", "尼斯", "里昂", "马赛");
        addIfContains(destinations, message, "意大利", "意大利", "罗马", "米兰", "佛罗伦萨", "威尼斯", "那不勒斯");
        addIfContains(destinations, message, "瑞士", "瑞士", "苏黎世", "日内瓦", "因特拉肯");
        addIfContains(destinations, message, "德国", "德国", "柏林", "慕尼黑", "法兰克福");
        addIfContains(destinations, message, "西班牙", "西班牙", "马德里", "巴塞罗那");
        addIfContains(destinations, message, "英国", "英国", "伦敦", "英格兰");
        addIfContains(destinations, message, "奥地利", "奥地利", "维也纳", "萨尔茨堡");
        addIfContains(destinations, message, "荷兰", "荷兰", "阿姆斯特丹");
        addIfContains(destinations, message, "捷克", "捷克", "布拉格");
        if (destinations.isEmpty() && (message.contains("欧洲") || message.toLowerCase(Locale.ROOT).contains("europe"))) {
            destinations.add("欧洲");
        }
        return new ArrayList<>(destinations);
    }

    private static void addIfContains(Set<String> destinations, String message, String canonical, String... aliases) {
        for (String alias : aliases) {
            if (hasText(alias) && message.contains(alias)) {
                destinations.add(canonical);
                return;
            }
        }
    }

    private static Integer extractDurationDays(String message) {
        if (!hasText(message)) {
            return null;
        }
        Matcher nightDay = NIGHT_DAY_PATTERN.matcher(message);
        if (nightDay.find()) {
            return Integer.parseInt(nightDay.group(2));
        }
        Matcher arabicWeek = ARABIC_WEEK_PATTERN.matcher(message);
        if (arabicWeek.find()) {
            return Integer.parseInt(arabicWeek.group(1)) * 7;
        }
        Matcher chineseWeek = CHINESE_WEEK_PATTERN.matcher(message);
        if (chineseWeek.find()) {
            Integer weeks = chineseNumberToInt(chineseWeek.group(1));
            return weeks == null ? null : weeks * 7;
        }
        Matcher arabicDay = ARABIC_DAY_PATTERN.matcher(message);
        if (arabicDay.find()) {
            return Integer.parseInt(arabicDay.group(1));
        }
        Matcher chineseDay = CHINESE_DAY_PATTERN.matcher(message);
        if (chineseDay.find()) {
            return chineseNumberToInt(chineseDay.group(1));
        }
        if (message.contains("一周")) {
            return 7;
        }
        return null;
    }

    private static Integer extractTravelerCount(String message) {
        if (!hasText(message)) {
            return null;
        }
        Matcher matcher = TRAVELER_PATTERN.matcher(message);
        if (matcher.find()) {
            return Integer.parseInt(matcher.group(1));
        }
        if (message.contains("一个人") || message.contains("独自") || message.contains("单人")) {
            return 1;
        }
        return null;
    }

    private static String extractDepartureCity(String message) {
        if (!hasText(message)) {
            return null;
        }
        Matcher matcher = DEPARTURE_PATTERN.matcher(message);
        if (matcher.find()) {
            return matcher.group(1).trim();
        }
        return null;
    }

    private static String extractStartDateText(String message) {
        if (!hasText(message)) {
            return null;
        }
        List<String> candidates = List.of("国庆", "春节", "暑假", "寒假", "圣诞", "复活节", "下个月", "明年", "今年");
        for (String candidate : candidates) {
            if (message.contains(candidate)) {
                return candidate;
            }
        }
        Matcher monthDay = Pattern.compile("(\\d{1,2})\\s*月\\s*(\\d{1,2})?\\s*(?:日|号)?").matcher(message);
        if (monthDay.find()) {
            return monthDay.group().trim();
        }
        return null;
    }

    private static BigDecimal extractBudgetAmount(String message) {
        Matcher matcher = budgetMatcher(message);
        if (matcher == null) {
            return null;
        }
        return new BigDecimal(matcher.group(1));
    }

    private static String extractBudgetCurrency(String message) {
        Matcher matcher = budgetMatcher(message);
        if (matcher == null) {
            return null;
        }
        return normalizeCurrency(matcher.group(2));
    }

    private static Matcher budgetMatcher(String message) {
        if (!hasText(message)) {
            return null;
        }
        Matcher matcher = BUDGET_PATTERN.matcher(message);
        while (matcher.find()) {
            String fullMatch = matcher.group();
            String currency = matcher.group(2);
            if (hasText(currency) || fullMatch.contains("预算") || fullMatch.contains("花费")) {
                return matcher;
            }
        }
        return null;
    }

    private static Boolean extractFlightBudgetBoundary(String message) {
        if (!hasText(message)) {
            return null;
        }
        String text = message.toLowerCase(Locale.ROOT);
        if (text.contains("不含国际机票")
                || text.contains("不包括国际机票")
                || text.contains("不含机票")
                || text.contains("不包括机票")
                || text.contains("机票自理")
                || text.contains("不算机票")) {
            return false;
        }
        if (text.contains("含国际机票")
                || text.contains("包括国际机票")
                || text.contains("包含国际机票")
                || text.contains("含机票")
                || text.contains("包括机票")) {
            return true;
        }
        return null;
    }

    private static List<String> extractPreferences(String message) {
        Set<String> values = new LinkedHashSet<>();
        if (!hasText(message)) {
            return new ArrayList<>();
        }
        addPreference(values, message, "小众", "小众", "冷门");
        addPreference(values, message, "美食", "美食", "吃", "餐厅");
        addPreference(values, message, "博物馆", "博物馆", "艺术馆", "美术馆");
        addPreference(values, message, "自然风光", "自然", "徒步", "山", "海");
        addPreference(values, message, "慢游", "慢游", "轻松", "不赶");
        return new ArrayList<>(values);
    }

    private static List<String> extractAvoidances(String message) {
        Set<String> values = new LinkedHashSet<>();
        if (!hasText(message)) {
            return new ArrayList<>();
        }
        addPreference(values, message, "避开人多", "避开人多", "人少", "不要人多");
        addPreference(values, message, "少去网红景点", "网红", "打卡点");
        addPreference(values, message, "不住青旅", "不住青旅", "不要青旅");
        return new ArrayList<>(values);
    }

    private static void addPreference(Set<String> values, String message, String value, String... triggers) {
        for (String trigger : triggers) {
            if (message.contains(trigger)) {
                values.add(value);
                return;
            }
        }
    }

    private static String extractTravelStyle(String message) {
        if (!hasText(message)) {
            return null;
        }
        if (message.contains("慢游") || message.contains("轻松") || message.contains("不赶")) {
            return "慢游";
        }
        if (message.contains("深度")) {
            return "深度游";
        }
        if (message.contains("高质量") || message.contains("舒适")) {
            return "舒适";
        }
        return null;
    }

    private static String extractAccommodationPreference(String message) {
        if (!hasText(message)) {
            return null;
        }
        if (message.contains("青旅")) {
            return message.contains("不住青旅") || message.contains("不要青旅") ? "不住青旅" : "青旅";
        }
        if (message.contains("民宿")) {
            return "民宿";
        }
        if (message.contains("经济酒店")) {
            return "经济酒店";
        }
        if (message.contains("中档酒店")) {
            return "中档酒店";
        }
        return null;
    }

    private static String extractTransportPreference(String message) {
        if (!hasText(message)) {
            return null;
        }
        if (message.contains("火车") || message.contains("欧铁")) {
            return "火车";
        }
        if (message.contains("自驾")) {
            return "自驾";
        }
        if (message.contains("公共交通")) {
            return "公共交通";
        }
        return null;
    }

    private static void mergeMissingFields(TravelRequirementSpec target, TravelRequirementSpec fallback) {
        if (target == null || fallback == null) {
            return;
        }
        if ((target.getDestinations() == null || target.getDestinations().isEmpty())
                && fallback.getDestinations() != null) {
            target.setDestinations(fallback.getDestinations());
        }
        if (!hasText(target.getDepartureCity())) {
            target.setDepartureCity(fallback.getDepartureCity());
        }
        if (!hasText(target.getStartDateText())) {
            target.setStartDateText(fallback.getStartDateText());
        }
        if (target.getDurationDays() == null) {
            target.setDurationDays(fallback.getDurationDays());
        }
        if (target.getTravelerCount() == null) {
            target.setTravelerCount(fallback.getTravelerCount());
        }
        if (target.getBudgetAmount() == null) {
            target.setBudgetAmount(fallback.getBudgetAmount());
        }
        if (!hasText(target.getBudgetCurrency())) {
            target.setBudgetCurrency(fallback.getBudgetCurrency());
        }
        if (target.getBudgetIncludesInternationalFlight() == null) {
            target.setBudgetIncludesInternationalFlight(fallback.getBudgetIncludesInternationalFlight());
        }
        if (target.getPreferences() == null || target.getPreferences().isEmpty()) {
            target.setPreferences(fallback.getPreferences());
        }
        if (target.getAvoidances() == null || target.getAvoidances().isEmpty()) {
            target.setAvoidances(fallback.getAvoidances());
        }
        if (!hasText(target.getTravelStyle())) {
            target.setTravelStyle(fallback.getTravelStyle());
        }
        if (!hasText(target.getAccommodationPreference())) {
            target.setAccommodationPreference(fallback.getAccommodationPreference());
        }
        if (!hasText(target.getTransportPreference())) {
            target.setTransportPreference(fallback.getTransportPreference());
        }
    }

    private static void normalize(TravelRequirementSpec spec, String sessionId, String message) {
        if (spec.getRequirementId() == null || spec.getRequirementId().isBlank()) {
            spec.setRequirementId("req-" + UUID.randomUUID());
        }
        if (!hasText(spec.getSessionId())) {
            spec.setSessionId(sessionId);
        }
        if (!hasText(spec.getOriginalMessage())) {
            spec.setOriginalMessage(message);
        }
        spec.setBudgetCurrency(normalizeCurrency(spec.getBudgetCurrency()));
        spec.setStatus(RequirementStatus.DRAFT);
    }

    private static String normalizeCurrency(String currency) {
        if (!hasText(currency)) {
            return null;
        }
        String value = currency.trim().toLowerCase(Locale.ROOT);
        return switch (value) {
            case "欧", "欧元", "eur", "€" -> "EUR";
            case "人民币", "元", "cny", "rmb" -> "CNY";
            case "美元", "美金", "usd", "$" -> "USD";
            case "英镑", "镑", "gbp", "£" -> "GBP";
            default -> currency.trim().toUpperCase(Locale.ROOT);
        };
    }

    private static String stripMarkdownFences(String raw) {
        if (!hasText(raw)) {
            return "";
        }
        String s = raw.strip();
        if (s.startsWith("```")) {
            int newline = s.indexOf('\n');
            s = newline >= 0 ? s.substring(newline + 1).strip() : s.substring(3).strip();
        }
        if (s.endsWith("```")) {
            s = s.substring(0, s.length() - 3).strip();
        }
        return s;
    }

    private static String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return "";
    }

    private static Integer chineseNumberToInt(String text) {
        if (!hasText(text)) {
            return null;
        }
        String normalized = text.trim().replace("两", "二");
        if ("十".equals(normalized)) {
            return 10;
        }
        if (normalized.contains("十")) {
            String[] parts = normalized.split("十", -1);
            int tens = parts[0].isEmpty() ? 1 : chineseDigit(parts[0]);
            int ones = parts.length > 1 && !parts[1].isEmpty() ? chineseDigit(parts[1]) : 0;
            return tens <= 0 || ones < 0 ? null : tens * 10 + ones;
        }
        int digit = chineseDigit(normalized);
        return digit > 0 ? digit : null;
    }

    private static int chineseDigit(String text) {
        return switch (text) {
            case "一" -> 1;
            case "二" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            default -> -1;
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
