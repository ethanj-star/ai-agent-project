package com.travel.agent.ai.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.ai.AiModelBeanNames;
import com.travel.agent.ai.graph.model.PlanModificationDecision;
import com.travel.agent.ai.graph.model.PlanModificationIntent;
import com.travel.agent.ai.graph.model.RequirementPatch;
import com.travel.agent.ai.graph.model.TravelPlanRecord;
import com.travel.agent.ai.graph.model.TravelPlanVersion;
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
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 计划修改意图识别智能体（AI 层 - 第六阶段修改路由器）。
 *
 * <p>系统架构位置：PlanController -> <b>PlanModificationAgent</b> -> PlanLocalRevisionNode / RequirementPatchNode</p>
 *
 * <p>职责：
 * <ul>
 *   <li>读取用户针对已有 planId 的自然语言修改指令。</li>
 *   <li>判断修改属于局部重写、核心需求变更、追问、普通评论或暂不支持。</li>
 *   <li>在核心需求变更时抽取 {@link RequirementPatch}，让系统回到第五阶段需求表确认。</li>
 *   <li>模型调用失败或输出异常时使用规则兜底，保证常见修改仍可处理。</li>
 * </ul>
 * </p>
 */
@Service
public class PlanModificationAgent {

    private static final Logger log = LoggerFactory.getLogger(PlanModificationAgent.class);

    private static final Pattern DAY_PATTERN = Pattern.compile("第\\s*([一二三四五六七八九十\\d]{1,3})\\s*天");
    private static final Pattern BUDGET_PATTERN =
            Pattern.compile("(?:预算|花费|价格)?\\s*(?:改成|改为|降到|控制在|变成)?\\s*(\\d{3,7})\\s*(欧元|欧|eur|€|人民币|元|cny|美元|美金|usd|英镑|镑|gbp)?",
                    Pattern.CASE_INSENSITIVE);
    private static final Pattern DURATION_PATTERN = Pattern.compile("(?:改成|改为|变成)?\\s*(\\d{1,2})\\s*(?:天|日)");
    private static final Pattern TRAVELER_PATTERN = Pattern.compile("(?:人数|人|变成|改成|改为)?\\s*(\\d{1,2})\\s*(?:个)?人");
    private static final Pattern DEPARTURE_PATTERN =
            Pattern.compile("从\\s*([\\u4e00-\\u9fa5A-Za-z]{2,20})\\s*(?:出发|起飞|走|过去)");

    private static final String SYSTEM_PROMPT = """
            你是旅行计划修改意图识别器，不是行程规划师。
            你的唯一任务是判断用户针对已有旅行计划想怎么修改，并输出 JSON。

            严格要求：
            1. 只输出合法 JSON Object，不要输出 Markdown。
            2. 不要生成新行程。
            3. 如果修改预算、目的地、天数、人数、出发城市、住宿偏好、交通偏好等核心需求，intent=REQUIREMENT_CHANGE。
            4. 如果只是调整某一天节奏、删减景点、增加美食、改写风险或预算说明，intent=LOCAL_REVISION。
            5. 如果用户表达不清，intent=CLARIFICATION，并给出 clarificationQuestion。
            6. 如果只是感谢或普通评论，intent=DIRECT_COMMENT。

            输出 JSON Schema：
            {
              "intent": "LOCAL_REVISION | REQUIREMENT_CHANGE | CLARIFICATION | DIRECT_COMMENT | UNSUPPORTED",
              "targetDay": "string or null",
              "targetSections": ["itinerary"],
              "instructionSummary": "string",
              "requirementPatch": {},
              "requiresConfirmation": true,
              "clarificationQuestion": "string or null"
            }
            """;

    /** 低成本模型客户端，用于修改意图识别。 */
    private final ChatClient chatClient;

    /** JSON 解析器。 */
    private final ObjectMapper objectMapper;

    /**
     * 构造器注入 Gatekeeper 模型和 Jackson。
     *
     * <p>修改意图识别是短 JSON 分类任务，使用低成本模型即可；复杂局部重写交给 PlanLocalRevisionNode。</p>
     *
     * @param chatModel    Gatekeeper ChatModel
     * @param objectMapper JSON 解析器
     */
    @Autowired
    public PlanModificationAgent(@Qualifier(AiModelBeanNames.GATEKEEPER_CHAT_MODEL) ChatModel chatModel,
                                 ObjectMapper objectMapper) {
        this(ChatClient.create(chatModel), objectMapper);
    }

    /**
     * 包内测试构造器。
     */
    PlanModificationAgent(ChatClient chatClient, ObjectMapper objectMapper) {
        this.chatClient = chatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 判断用户修改意图。
     *
     * <p>处理流程：
     * <ol>
     *   <li>先用规则生成兜底决策，覆盖预算、天数、目的地、局部日期等常见修改。</li>
     *   <li>调用低成本模型识别更灵活表达。</li>
     *   <li>解析模型 JSON，失败时返回规则兜底。</li>
     *   <li>用规则补丁补齐模型遗漏的确定性字段。</li>
     * </ol>
     * </p>
     *
     * @param record          当前计划记录
     * @param userInstruction 用户自然语言修改指令
     * @return 结构化修改决策
     */
    public PlanModificationDecision decide(TravelPlanRecord record, String userInstruction) {
        // 先算规则兜底：即使模型不可用，也能处理预算、天数、局部日期等高确定性修改。
        PlanModificationDecision fallback = decideByRules(userInstruction);
        if (chatClient == null || !hasText(userInstruction)) {
            return fallback;
        }

        try {
            // 模型负责理解更灵活的自然语言表达；规则结果仍保留，用来补模型漏掉的确定性字段。
            String raw = callModel(record, userInstruction);
            PlanModificationDecision modelDecision = parseOrFallback(raw, fallback);
            mergeRulePatch(modelDecision, fallback);
            return modelDecision;
        } catch (Exception e) {
            // 修改入口不能因为分类模型失败而中断，返回规则结果让后续流程继续可用。
            log.warn("[PlanModification] model call failed, use rule fallback: {}", e.getMessage());
            return fallback;
        }
    }

    /**
     * 调用低成本模型识别修改意图。
     */
    protected String callModel(TravelPlanRecord record, String userInstruction) {
        // 这里只让模型做“意图分类 + 补丁抽取”，不允许它直接重写旅行计划。
        return chatClient.prompt()
                .system(SYSTEM_PROMPT)
                .user(buildUserPrompt(record, userInstruction))
                .call()
                .content();
    }

    /**
     * 解析模型输出，失败时返回规则兜底。
     */
    PlanModificationDecision parseOrFallback(String raw, PlanModificationDecision fallback) {
        // 模型有时会包 Markdown 代码块，先清理再交给 Jackson。
        String cleaned = stripMarkdownFences(raw);
        if (!hasText(cleaned)) {
            return fallback;
        }
        try {
            return objectMapper.readValue(cleaned, PlanModificationDecision.class);
        } catch (Exception first) {
            // 如果整段解析失败，尝试从文本中截取第一个 JSON Object；这是对模型多说废话的第二层兜底。
            String json = extractJsonObject(cleaned);
            if (hasText(json)) {
                try {
                    return objectMapper.readValue(json, PlanModificationDecision.class);
                } catch (Exception ignored) {
                    log.warn("[PlanModification] extracted JSON parse failed: {}", ignored.getMessage());
                }
            }
            return fallback;
        }
    }

    /**
     * 规则兜底修改意图识别。
     *
     * <p>规则只覆盖高确定性修改，不尝试复杂推理；复杂表达仍交给模型。</p>
     */
    static PlanModificationDecision decideByRules(String instruction) {
        PlanModificationDecision decision = new PlanModificationDecision();
        decision.setInstructionSummary(defaultText(instruction, "用户提出了计划修改请求。"));

        // 空指令不能猜测修改目标，必须追问用户。
        if (!hasText(instruction)) {
            decision.setIntent(PlanModificationIntent.CLARIFICATION);
            decision.setClarificationQuestion("你想修改哪一天或哪一部分行程？");
            return decision;
        }

        // “谢谢”“先看看”这类反馈不应触发重写或重新生成。
        if (looksLikeDirectComment(instruction)) {
            decision.setIntent(PlanModificationIntent.DIRECT_COMMENT);
            decision.setInstructionSummary("用户只是反馈或暂时查看，不需要修改计划。");
            return decision;
        }

        // 预算、天数、目的地、人数等属于核心需求变更，需要回到需求表确认，而不是直接局部改草稿。
        RequirementPatch patch = extractRequirementPatch(instruction);
        if (hasRequirementChange(instruction, patch)) {
            decision.setIntent(PlanModificationIntent.REQUIREMENT_CHANGE);
            decision.setRequirementPatch(patch);
            decision.setRequiresConfirmation(true);
            decision.setInstructionSummary("用户修改了核心旅行需求，需要重新确认需求表。");
            return decision;
        }

        // 明确提到某一天、节奏、删减景点等，通常可以在当前计划文本上做局部重写。
        if (looksLikeLocalRevision(instruction)) {
            decision.setIntent(PlanModificationIntent.LOCAL_REVISION);
            decision.setTargetDay(extractTargetDay(instruction));
            decision.setTargetSections(List.of("itinerary"));
            decision.setInstructionSummary(instruction.trim());
            return decision;
        }

        // 指令过短或太泛时，直接改计划很容易误解用户意图，先追问。
        if (looksTooVague(instruction)) {
            decision.setIntent(PlanModificationIntent.CLARIFICATION);
            decision.setClarificationQuestion("你是想修改某一天的安排，还是想调整预算、目的地、住宿等核心需求？");
            return decision;
        }

        decision.setIntent(PlanModificationIntent.LOCAL_REVISION);
        decision.setTargetSections(List.of("itinerary"));
        decision.setInstructionSummary(instruction.trim());
        return decision;
    }

    private static RequirementPatch extractRequirementPatch(String instruction) {
        RequirementPatch patch = new RequirementPatch();
        if (!hasText(instruction)) {
            return patch;
        }

        // 预算正则可能命中普通数字；只有出现预算语义或货币单位时才采纳，避免误把“第3天”当预算。
        Matcher budget = BUDGET_PATTERN.matcher(instruction);
        while (budget.find()) {
            if (instruction.contains("预算") || instruction.contains("花费") || hasText(budget.group(2))) {
                patch.setBudgetAmount(new BigDecimal(budget.group(1)));
                patch.setBudgetCurrency(normalizeCurrency(budget.group(2)));
                break;
            }
        }

        // 天数、人数字段只在用户出现“改成/天数/人数”等修改语义时采纳。
        Matcher duration = DURATION_PATTERN.matcher(instruction);
        if ((instruction.contains("天数") || instruction.contains("行程") || instruction.contains("改成")
                || instruction.contains("改为")) && duration.find()) {
            patch.setDurationDays(Integer.parseInt(duration.group(1)));
        }

        Matcher traveler = TRAVELER_PATTERN.matcher(instruction);
        if ((instruction.contains("人数") || instruction.contains("变成") || instruction.contains("改成")) && traveler.find()) {
            patch.setTravelerCount(Integer.parseInt(traveler.group(1)));
        }

        Matcher departure = DEPARTURE_PATTERN.matcher(instruction);
        if (departure.find()) {
            patch.setDepartureCity(departure.group(1).trim());
        }

        List<String> destinations = extractDestinationPatch(instruction);
        if (!destinations.isEmpty()) {
            patch.setDestinations(destinations);
        }

        if (instruction.contains("经济酒店") || instruction.contains("经济型酒店")) {
            patch.setAccommodationPreference("经济型酒店");
        } else if (instruction.contains("中档酒店")) {
            patch.setAccommodationPreference("中档酒店");
        } else if (instruction.contains("民宿")) {
            patch.setAccommodationPreference("民宿");
        } else if (instruction.contains("不住青旅") || instruction.contains("不要青旅")) {
            patch.setAccommodationPreference("不住青旅");
        }

        if (instruction.contains("火车") || instruction.contains("欧铁")) {
            patch.setTransportPreference("火车");
        } else if (instruction.contains("自驾")) {
            patch.setTransportPreference("自驾");
        }

        patch.setAddPreferences(extractAddedPreferences(instruction));
        patch.setAddAvoidances(extractAddedAvoidances(instruction));
        if (instruction.contains("不含国际机票") || instruction.contains("不算机票")) {
            patch.setBudgetIncludesInternationalFlight(false);
        } else if (instruction.contains("含国际机票") || instruction.contains("包含机票")) {
            patch.setBudgetIncludesInternationalFlight(true);
        }
        return patch;
    }

    private static boolean hasRequirementChange(String instruction, RequirementPatch patch) {
        if (!hasText(instruction) || patch == null) {
            return false;
        }
        return patch.getBudgetAmount() != null
                || patch.getDurationDays() != null
                || patch.getTravelerCount() != null
                || patch.getBudgetIncludesInternationalFlight() != null
                || hasText(patch.getDepartureCity())
                || hasText(patch.getAccommodationPreference())
                || hasText(patch.getTransportPreference())
                || (patch.getDestinations() != null && !patch.getDestinations().isEmpty())
                || instruction.contains("预算")
                || instruction.contains("天数")
                || instruction.contains("目的地")
                || instruction.contains("人数")
                || instruction.contains("住");
    }

    private static List<String> extractDestinationPatch(String instruction) {
        Set<String> destinations = new LinkedHashSet<>();
        if (!instruction.contains("目的地")
                && !instruction.contains("加")
                && !instruction.contains("增加")
                && !instruction.contains("加入")
                && !instruction.contains("改成")
                && !instruction.contains("改为")) {
            return new ArrayList<>();
        }
        addIfContains(destinations, instruction, "法国", "法国", "巴黎", "尼斯", "里昂");
        addIfContains(destinations, instruction, "意大利", "意大利", "罗马", "米兰", "佛罗伦萨", "威尼斯");
        addIfContains(destinations, instruction, "瑞士", "瑞士", "苏黎世", "日内瓦", "因特拉肯");
        addIfContains(destinations, instruction, "德国", "德国", "柏林", "慕尼黑");
        addIfContains(destinations, instruction, "西班牙", "西班牙", "马德里", "巴塞罗那");
        return new ArrayList<>(destinations);
    }

    private static void addIfContains(Set<String> destinations, String instruction, String canonical, String... aliases) {
        for (String alias : aliases) {
            if (instruction.contains(alias)) {
                destinations.add(canonical);
                return;
            }
        }
    }

    private static List<String> extractAddedPreferences(String instruction) {
        List<String> values = new ArrayList<>();
        if (instruction.contains("美食")) {
            values.add("美食");
        }
        if (instruction.contains("博物馆") || instruction.contains("美术馆")) {
            values.add("博物馆");
        }
        if (instruction.contains("慢游") || instruction.contains("轻松")) {
            values.add("慢游");
        }
        return values;
    }

    private static List<String> extractAddedAvoidances(String instruction) {
        List<String> values = new ArrayList<>();
        if (instruction.contains("避开人多") || instruction.contains("人少")) {
            values.add("避开人多");
        }
        if (instruction.contains("不要徒步") || instruction.contains("不徒步")) {
            values.add("不要徒步");
        }
        if (instruction.contains("不住青旅") || instruction.contains("不要青旅")) {
            values.add("不住青旅");
        }
        return values;
    }

    private static boolean looksLikeDirectComment(String instruction) {
        String text = instruction.trim();
        return text.equals("谢谢")
                || text.equals("谢谢你")
                || text.contains("不错")
                || text.contains("挺好")
                || text.contains("先看看")
                || text.contains("我先看看")
                || text.contains("可以了");
    }

    private static boolean looksLikeLocalRevision(String instruction) {
        return DAY_PATTERN.matcher(instruction).find()
                || instruction.contains("太赶")
                || instruction.contains("少安排")
                || instruction.contains("自由时间")
                || instruction.contains("多加")
                || instruction.contains("加一点")
                || instruction.contains("不要徒步")
                || instruction.contains("换成室内")
                || instruction.contains("轻松一点");
    }

    private static boolean looksTooVague(String instruction) {
        String text = instruction.trim();
        return text.length() <= 8
                || text.contains("改改")
                || text.contains("换一下")
                || text.contains("合理点");
    }

    private static String extractTargetDay(String instruction) {
        Matcher matcher = DAY_PATTERN.matcher(instruction);
        if (matcher.find()) {
            return "第" + matcher.group(1).trim() + "天";
        }
        return null;
    }

    private static void mergeRulePatch(PlanModificationDecision target, PlanModificationDecision fallback) {
        if (target == null || fallback == null) {
            return;
        }
        // 模型输出可能缺字段；规则结果是确定性较强的保底信息，用它补空，不覆盖模型明确判断。
        if (target.getIntent() == null || target.getIntent() == PlanModificationIntent.UNSUPPORTED) {
            target.setIntent(fallback.getIntent());
        }
        if (!hasText(target.getInstructionSummary())) {
            target.setInstructionSummary(fallback.getInstructionSummary());
        }
        if (!hasText(target.getTargetDay())) {
            target.setTargetDay(fallback.getTargetDay());
        }
        if ((target.getRequirementPatch() == null || !patchHasData(target.getRequirementPatch()))
                && fallback.getRequirementPatch() != null) {
            target.setRequirementPatch(fallback.getRequirementPatch());
        }
        if (!hasText(target.getClarificationQuestion())) {
            target.setClarificationQuestion(fallback.getClarificationQuestion());
        }
    }

    private static boolean patchHasData(RequirementPatch patch) {
        return patch.getBudgetAmount() != null
                || patch.getDurationDays() != null
                || patch.getTravelerCount() != null
                || patch.getBudgetIncludesInternationalFlight() != null
                || hasText(patch.getDepartureCity())
                || hasText(patch.getAccommodationPreference())
                || hasText(patch.getTransportPreference())
                || (patch.getDestinations() != null && !patch.getDestinations().isEmpty());
    }

    private static String buildUserPrompt(TravelPlanRecord record, String userInstruction) {
        // 只截取当前计划摘要的一小段给分类模型，避免把完整长行程塞进低成本路由任务。
        TravelPlanVersion current = record == null ? null : record.current().orElse(null);
        String currentAnswer = current == null ? "无当前计划文本。" : defaultText(current.getFinalAnswer(), "无当前计划文本。");
        return """
                用户修改指令：
                %s

                当前计划摘要：
                %s
                """.formatted(userInstruction, truncate(currentAnswer, 1200));
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

    private static String truncate(String value, int maxLength) {
        if (!hasText(value) || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    private static String defaultText(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
