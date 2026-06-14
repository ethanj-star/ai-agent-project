package com.travel.agent.ai.graph.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.ai.AiModelBeanNames;
import com.travel.agent.ai.graph.model.BranchDispatchDecision;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

/**
 * 模型驱动的分支任务建议节点。
 *
 * <p>系统架构位置：RetrieveKnowledgeNode -> <b>ModelBranchDispatchNode</b> -> BranchDispatchGuardNode</p>
 *
 * <p>职责：
 * <ul>
 *   <li>读取 {@link TravelPlanState} 中的用户原文、结构化需求表、RAG 摘要和已解析字段。</li>
 *   <li>调用 DeepSeek Pro 判断本轮规划应该尝试哪些分支任务。</li>
 *   <li>只输出 {@link BranchDispatchDecision}，不直接写 branchTasks，也不调用任何外部工具。</li>
 *   <li>模型调用失败、输出空内容或 JSON 解析失败时，返回 fallback 决策，让旧规则派发继续兜底。</li>
 * </ul>
 * </p>
 *
 * <p>设计边界：本节点负责“理解和建议”，真正的安全边界在 BranchDispatchGuardNode。
 * 这样模型无法越过 Java 白名单直接调用不存在的工具。</p>
 */
@Component
public class ModelBranchDispatchNode {

    private static final Logger log = LoggerFactory.getLogger(ModelBranchDispatchNode.class);

    /** DeepSeek Pro 对应的 ChatClient，用于高层任务派发判断。 */
    private final ChatClient coreChatClient;

    /** 解析模型 JSON 输出为 BranchDispatchDecision 的 Jackson 工具。 */
    private final ObjectMapper objectMapper;

    /**
     * Spring 生产环境构造器。
     *
     * <p>这里显式注入 {@code coreChatModel}，避免误用被标记为 Primary 的 Qwen 分支模型。</p>
     *
     * @param coreChatModel DeepSeek Pro 模型 Bean
     * @param objectMapper  JSON 解析器
     */
    @Autowired
    public ModelBranchDispatchNode(@Qualifier(AiModelBeanNames.CORE_CHAT_MODEL) ChatModel coreChatModel,
                                   ObjectMapper objectMapper) {
        this(ChatClient.create(coreChatModel), objectMapper);
    }

    /**
     * 包内测试构造器。
     *
     * <p>测试可以传入 null ChatClient 或覆写 {@link #callModel(String, String)}，避免触发真实模型调用。</p>
     */
    ModelBranchDispatchNode(ChatClient coreChatClient, ObjectMapper objectMapper) {
        this.coreChatClient = coreChatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 让核心模型提出分支任务建议。
     *
     * <p>处理流程：</p>
     * <ol>
     *   <li>构建只包含可用工具和当前状态的派发提示词。</li>
     *   <li>调用 DeepSeek Pro，要求只返回 BranchDispatchDecision JSON。</li>
     *   <li>解析 JSON；如果失败，返回 fallback 决策。</li>
     * </ol>
     *
     * <p>失败策略：模型派发只是增强层，失败时不影响主流程，Guard 会回退旧 BranchDispatchNode。</p>
     *
     * @param state 当前旅行规划状态
     * @return 模型派发决策；失败时 {@code fallbackRequired=true}
     */
    public BranchDispatchDecision dispatch(TravelPlanState state) {
        if (state == null) {
            return BranchDispatchDecision.fallback("TravelPlanState 为空，无法执行模型派发。");
        }
        if (coreChatClient == null) {
            return BranchDispatchDecision.fallback("模型派发节点未配置 coreChatClient。");
        }

        try {
            String systemPrompt = buildSystemPrompt(state);
            String rawResponse = callModel(systemPrompt, state.getUserQuery());
            BranchDispatchDecision decision = parseDecision(rawResponse);
            if (decision.getTasks().isEmpty()) {
                decision.setFallbackRequired(true);
                decision.setFallbackReason("模型没有返回任何分支任务建议。");
            }
            log.info("[Graph][ModelBranchDispatch] suggested={}, fallback={}",
                    decision.getTasks().size(),
                    decision.isFallbackRequired());
            return decision;
        } catch (Exception e) {
            log.warn("[Graph][ModelBranchDispatch] model dispatch failed: {}", e.getMessage());
            return BranchDispatchDecision.fallback("模型派发失败：" + e.getMessage());
        }
    }

    /**
     * 调用核心模型。
     *
     * <p>拆成独立方法是为了单元测试覆盖模型边界。生产环境要求模型只返回 JSON，不输出 Markdown 或解释文字。</p>
     */
    protected String callModel(String systemPrompt, String userQuery) {
        return coreChatClient.prompt()
                .system(systemPrompt)
                .user(hasText(userQuery) ? userQuery : "请根据当前旅行规划状态建议需要派发的分支任务。")
                .call()
                .content();
    }

    /**
     * 构建分支派发 Prompt。
     *
     * <p>Prompt 明确写出当前真实可用的工具边界，尤其是 current weather 不能当未来天气预报，
     * 以及未接入的签证、火车、餐厅等类型不能作为可执行任务输出。</p>
     */
    String buildSystemPrompt(TravelPlanState state) {
        TravelRequirementSpec spec = state.getRequirementSpec();
        return """
                你是旅行 Agent 工作流中的 ModelBranchDispatchNode。你的任务是判断本轮规划需要哪些分支任务。

                严格要求：
                1. 只输出合法 JSON Object，不要输出 Markdown，不要输出解释文字。
                2. 你只是在“建议任务”，不是直接调用工具。
                3. type 只能从以下枚举中选择：KNOWLEDGE, WEATHER, PLACES, FLIGHT, HOTEL。
                4. 不要输出 VISA, TRAIN, RESTAURANT, FORECAST_WEATHER, OPENING_HOURS 等当前系统未接入的工具类型。
                5. WEATHER 当前只是真实“当前天气”工具，不是未来日期天气预报；未来旅行不要为了天气预报派发 WEATHER。
                6. FLIGHT 需要出发地、目的地和明确 startDate；“不含国际机票”只是预算边界，不是查航班请求。
                7. HOTEL 需要目的地、startDate 和 durationDays；用户有住宿偏好或预算规划需求时可以建议 HOTEL。
                8. 每种任务最多建议一次，最多建议 5 个任务。
                9. 当前系统日期：%s。

                可用任务说明：
                - KNOWLEDGE：补充攻略、防坑、交通经验、非实时背景信息。
                - PLACES：查询代表城市热门景点和 POI 线索。
                - WEATHER：查询当前/今天/实时天气，不能作为未来日期精准天气。
                - FLIGHT：查询入境航班参考，需要 departureCity + destination + startDate。
                - HOTEL：查询住宿参考，需要 destination + startDate + durationDays。

                用户原始输入：
                %s

                已确认结构化需求表：
                %s

                已解析状态：
                - 目的地：%s
                - 出行时间：%s
                - 行程天数：%s
                - 关键词：%s

                RAG 摘要：
                %s

                输出 JSON Schema：
                {
                  "tasks": [
                    {
                      "type": "HOTEL",
                      "priority": "HIGH",
                      "reason": "用户提供明确日期、天数和住宿偏好，需要真实酒店价格参考。"
                    }
                  ],
                  "notes": [
                    "未来天气工具暂未接入，因此没有建议 WEATHER。"
                  ]
                }
                """.formatted(
                LocalDate.now(),
                defaultText(state.getUserQuery(), "未提供"),
                formatRequirementSpec(spec),
                state.getDestinations() == null || state.getDestinations().isEmpty()
                        ? "未指定"
                        : String.join("、", state.getDestinations()),
                defaultText(state.getTravelTime(), "未指定"),
                state.getDurationDays() == null ? "未指定" : state.getDurationDays() + "天",
                state.getKeywords() == null || state.getKeywords().isEmpty()
                        ? "无"
                        : String.join("、", state.getKeywords()),
                truncate(defaultText(state.getRagContext(), "无"), 800)
        );
    }

    /**
     * 解析模型派发 JSON。
     *
     * <p>模型偶尔会包 Markdown fence 或在 JSON 前后加说明文字；这里和 Planner/Risk 节点一样温和修复。
     * 如果仍无法解析，抛出异常，由 {@link #dispatch(TravelPlanState)} 转为 fallback 决策。</p>
     */
    BranchDispatchDecision parseDecision(String rawResponse) throws Exception {
        String cleaned = stripMarkdownFences(rawResponse);
        if (!hasText(cleaned)) {
            return BranchDispatchDecision.fallback("模型返回为空。");
        }
        try {
            return objectMapper.readValue(cleaned, BranchDispatchDecision.class);
        } catch (Exception first) {
            String jsonObject = extractJsonObject(cleaned);
            if (hasText(jsonObject)) {
                return objectMapper.readValue(jsonObject, BranchDispatchDecision.class);
            }
            throw first;
        }
    }

    private static String formatRequirementSpec(TravelRequirementSpec spec) {
        if (spec == null) {
            return "无。";
        }
        List<String> lines = new java.util.ArrayList<>();
        if (spec.getDestinations() != null && !spec.getDestinations().isEmpty()) {
            lines.add("- 目的地：" + String.join("、", spec.getDestinations()));
        }
        if (hasText(spec.getDepartureCity())) {
            lines.add("- 出发城市：" + spec.getDepartureCity());
        }
        if (spec.getStartDate() != null) {
            lines.add("- 出发日期：" + spec.getStartDate());
        } else if (hasText(spec.getStartDateText())) {
            lines.add("- 时间描述：" + spec.getStartDateText());
        }
        if (spec.getDurationDays() != null) {
            lines.add("- 行程天数：" + spec.getDurationDays() + "天");
        }
        if (spec.getBudgetAmount() != null) {
            lines.add("- 预算：" + spec.getBudgetAmount().stripTrailingZeros().toPlainString()
                    + defaultText(spec.getBudgetCurrency(), ""));
        }
        if (spec.getBudgetIncludesInternationalFlight() != null) {
            lines.add("- 国际机票边界：" + (spec.getBudgetIncludesInternationalFlight() ? "预算包含国际机票" : "预算不含国际机票"));
        }
        if (hasText(spec.getAccommodationPreference())) {
            lines.add("- 住宿偏好：" + spec.getAccommodationPreference());
        }
        if (spec.getPreferences() != null && !spec.getPreferences().isEmpty()) {
            lines.add("- 偏好：" + String.join("、", spec.getPreferences()));
        }
        if (spec.getAvoidances() != null && !spec.getAvoidances().isEmpty()) {
            lines.add("- 避开：" + String.join("、", spec.getAvoidances()));
        }
        if (hasText(spec.getSpecialNotes())) {
            lines.add("- 特殊要求：" + spec.getSpecialNotes());
        }
        return lines.isEmpty() ? "无。" : String.join("\n", lines);
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
        return hasText(value) ? value.trim() : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
