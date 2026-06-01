package com.travel.agent.ai.graph.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.ai.AiModelBeanNames;
import com.travel.agent.ai.graph.model.BranchResult;
import com.travel.agent.ai.graph.model.PlannerDraft;
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
import java.util.ArrayList;
import java.util.List;

/**
 * Planner 草案生成节点（Graph 层 - LLM 规划核心）。
 *
 * <p>系统架构位置：LangGraphPlannerFacade → <b>PlanDraftNode</b> → DeepSeek Pro</p>
 *
 * <p>职责：
 * <ul>
 *   <li>读取 {@link TravelPlanState} 中的用户输入、已确认需求表、Gatekeeper 实体、行程时长、RAG 上下文和分支 Agent 结果。</li>
 *   <li>调用核心模型生成结构化 {@link PlannerDraft}。</li>
 *   <li>约束模型输出 JSON，便于后续 Validator 和 Finalizer 使用。</li>
 *   <li>模型输出为空、非 JSON 或调用失败时生成兜底草案，保证直线流程不中断。</li>
 * </ul>
 * </p>
 */
@Component
public class PlanDraftNode {

    private static final Logger log = LoggerFactory.getLogger(PlanDraftNode.class);

    /** DeepSeek Pro 对应的 ChatClient，仅在 PLAN_OR_RAG 工作流中使用 */
    private final ChatClient coreChatClient;

    /** 解析模型 JSON 输出为 PlannerDraft 的 Jackson 工具 */
    private final ObjectMapper objectMapper;

    /**
     * 构造器注入核心模型和 Jackson。
     *
     * <p>这里显式使用 {@code coreChatModel}，避免误用系统默认的 {@code branchChatModel}。
     * 测试场景则通过包内构造器注入 mock / null ChatClient，只测试纯解析逻辑。</p>
     *
     * @param coreChatModel DeepSeek Pro 模型 Bean
     * @param objectMapper  JSON 解析器
     */
    @Autowired
    public PlanDraftNode(@Qualifier(AiModelBeanNames.CORE_CHAT_MODEL) ChatModel coreChatModel,
                         ObjectMapper objectMapper) {
        this(ChatClient.create(coreChatModel), objectMapper);
    }

    /**
     * 包内测试构造器。
     *
     * <p>生产环境由 Spring 使用公开构造器；测试中可以不触发真实大模型调用。</p>
     */
    PlanDraftNode(ChatClient coreChatClient, ObjectMapper objectMapper) {
        this.coreChatClient = coreChatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 调用核心模型生成规划草案并写回状态。
     *
     * <p>处理流程：
     * <ol>
     *   <li>根据当前状态构建系统提示词，注入 RAG 上下文和当前日期。</li>
     *   <li>调用 DeepSeek Pro 生成 JSON 草案。</li>
     *   <li>解析 JSON 为 {@link PlannerDraft}；解析失败时降级为 Markdown 草案。</li>
     *   <li>补齐草案中的空字段，避免后续节点处理 null。</li>
     * </ol>
     * </p>
     *
     * <p>异常策略：模型调用失败时写入兜底草案，不向上传播异常。</p>
     *
     * @param state 当前旅行规划状态
     * @return 写入 draft 后的状态
     */
    public TravelPlanState plan(TravelPlanState state) {
        if (state == null) {
            TravelPlanState fallback = new TravelPlanState();
            fallback.setDraft(buildFallbackDraft("规划状态为空，无法生成完整草案。"));
            return fallback;
        }

        String systemPrompt = buildSystemPrompt(state);
        log.info("[Graph][PlanDraft] planning with destinations={}, time={}",
                state.getDestinations(), state.getTravelTime());

        try {
            String rawResponse = callModel(systemPrompt, state.getUserQuery());
            PlannerDraft draft = parseOrFallback(rawResponse);
            normalizeDraft(draft, state);
            state.setDraft(draft);
            return state;
        } catch (Exception e) {
            log.error("[Graph][PlanDraft] model call failed: {}", e.getMessage());
            state.setDraft(buildFallbackDraft(
                    "规划模型暂时不可用，建议稍后重试，或先补充目的地、日期、预算等关键信息。"));
            return state;
        }
    }

    /**
     * 调用核心模型。
     *
     * <p>拆成独立方法是为了测试时可以覆盖模型调用边界；第一阶段单元测试不触发真实 API。</p>
     *
     * @param systemPrompt 已注入状态和 RAG 的系统提示词
     * @param userQuery    用户原始输入
     * @return 模型原始文本响应
     */
    protected String callModel(String systemPrompt, String userQuery) {
        return coreChatClient.prompt()
                .system(systemPrompt)
                .user(hasText(userQuery) ? userQuery : "请根据当前状态生成欧洲旅行规划草案。")
                .call()
                .content();
    }

    /**
     * 构建 Planner 系统提示词。
     *
     * <p>提示词中同时放入用户原文、Gatekeeper 结构化实体、RAG 上下文和当前日期。
     * 这样核心模型既能看到自然语言细节，也能利用上游节点提取出的稳定字段。
     * 第三阶段开始额外注入分支 Agent 结果，让 Planner 可以区分工具确认信息和工具失败降级信息。
     * 第五阶段开始额外注入已确认 TravelRequirementSpec，要求 Planner 优先服从表单字段。</p>
     *
     * @param state 当前旅行规划状态
     * @return 完整系统提示词
     */
    String buildSystemPrompt(TravelPlanState state) {
        String destinations = state.getDestinations() == null || state.getDestinations().isEmpty()
                ? "未指定"
                : String.join("、", state.getDestinations());
        String keywords = state.getKeywords() == null || state.getKeywords().isEmpty()
                ? "无"
                : String.join("、", state.getKeywords());
        String duration = hasText(state.getDurationText())
                ? state.getDurationText()
                : "未指定";
        String ragContext = hasText(state.getRagContext())
                ? state.getRagContext()
                : "私有知识库暂无可用上下文。";
        String branchContext = formatBranchResults(state.getBranchResults());
        String requirementContext = formatRequirementSpec(state.getRequirementSpec());

        return """
                你是一个欧洲旅行规划系统中的 Planner 节点。你的任务是基于用户输入、结构化意图和 RAG 上下文，生成第一版旅行规划草案。

                严格要求：
                1. 只输出一个合法 JSON Object，不要输出 Markdown 代码块，不要输出解释文字。
                2. 不要编造已经由工具才能确认的实时价格、实时航班或实时天气。
                3. 如果用户信息缺失，可以在 assumptions 中说明你做出的假设。
                4. 如果行程时长已知，推荐行程必须尽量匹配该天数，不要随意拉长或缩短。
                5. 分支 Agent 成功返回的数据可以作为已确认参考；分支失败或未启用时，只能写风险提醒，不能伪造实时结果。
                6. 行程建议要具体，优先使用 RAG 上下文中的防坑、POI、交通经验。
                7. 如果“已确认结构化需求表”不为空，它是优先级最高的用户事实来源，不得被原始自然语言或模型假设覆盖。
                8. 当前系统日期是：%s。

                用户原始输入：
                %s

                已确认结构化需求表：
                %s

                Gatekeeper 提取信息：
                - 目的地：%s
                - 出行时间：%s
                - 行程时长：%s
                - 关键词：%s

                RAG 上下文：
                %s

                分支 Agent 结果：
                %s

                输出 JSON Schema：
                {
                  "title": "string",
                  "summary": "string",
                  "itineraryMarkdown": "string",
                  "budgetNotes": "string",
                  "riskNotes": "string",
                  "assumptions": ["string"]
                }
                """.formatted(
                LocalDate.now(),
                defaultText(state.getUserQuery(), "未提供"),
                requirementContext,
                destinations,
                defaultText(state.getTravelTime(), "未指定"),
                duration,
                keywords,
                ragContext,
                branchContext
        );
    }

    /**
     * 将第五阶段确认后的结构化需求表压缩成 Planner 可读上下文。
     *
     * <p>这里不直接序列化完整对象，是为了保持 prompt 短而稳定；只注入规划真正需要遵守的字段。
     * 当需求表为空时，旧的自然语言入口仍按前四阶段逻辑工作。</p>
     */
    private static String formatRequirementSpec(TravelRequirementSpec spec) {
        if (spec == null) {
            return "无。";
        }
        List<String> lines = new ArrayList<>();
        if (spec.getDestinations() != null && !spec.getDestinations().isEmpty()) {
            lines.add("- 目的地：" + String.join("、", spec.getDestinations()));
        }
        if (hasText(spec.getDepartureCity())) {
            lines.add("- 出发城市：" + spec.getDepartureCity());
        }
        if (hasText(spec.getStartDateText())) {
            lines.add("- 出行时间：" + spec.getStartDateText());
        } else if (spec.getStartDate() != null) {
            lines.add("- 出行日期：" + spec.getStartDate());
        }
        if (spec.getDurationDays() != null) {
            lines.add("- 行程天数：" + spec.getDurationDays() + "天");
        }
        if (spec.getTravelerCount() != null) {
            lines.add("- 旅行人数：" + spec.getTravelerCount() + "人");
        }
        if (spec.getBudgetAmount() != null) {
            lines.add("- 预算：" + spec.getBudgetAmount().stripTrailingZeros().toPlainString()
                    + defaultText(spec.getBudgetCurrency(), ""));
        }
        if (spec.getBudgetIncludesInternationalFlight() != null) {
            lines.add("- 国际机票边界：" + (spec.getBudgetIncludesInternationalFlight() ? "预算包含国际机票" : "预算不含国际机票"));
        }
        if (spec.getPreferences() != null && !spec.getPreferences().isEmpty()) {
            lines.add("- 偏好：" + String.join("、", spec.getPreferences()));
        }
        if (spec.getAvoidances() != null && !spec.getAvoidances().isEmpty()) {
            lines.add("- 避开：" + String.join("、", spec.getAvoidances()));
        }
        if (hasText(spec.getTravelStyle())) {
            lines.add("- 旅行风格：" + spec.getTravelStyle());
        }
        if (hasText(spec.getAccommodationPreference())) {
            lines.add("- 住宿偏好：" + spec.getAccommodationPreference());
        }
        if (hasText(spec.getTransportPreference())) {
            lines.add("- 交通偏好：" + spec.getTransportPreference());
        }
        return lines.isEmpty() ? "无。" : String.join("\n", lines);
    }

    /**
     * 将分支 Agent 结果压缩成适合注入 Planner Prompt 的文本。
     *
     * <p>只给 Planner 传递短摘要和必要原始数据，避免工具返回过长内容挤占核心模型上下文。
     * 失败结果也会显式写入，提醒模型不要编造尚未确认的实时信息。</p>
     */
    private static String formatBranchResults(List<BranchResult> branchResults) {
        if (branchResults == null || branchResults.isEmpty()) {
            return "暂无分支 Agent 结果。";
        }

        StringBuilder builder = new StringBuilder();
        for (BranchResult result : branchResults) {
            if (result == null) {
                continue;
            }
            builder.append("- ")
                    .append(result.getType() == null ? "UNKNOWN" : result.getType())
                    .append(result.isSuccess() ? " [SUCCESS] " : " [FAILED] ")
                    .append(defaultText(result.getSummary(), "无摘要"));
            if (hasText(result.getRawData())) {
                builder.append(" 原始数据：")
                        .append(truncate(result.getRawData(), 300));
            }
            if (!result.isSuccess() && hasText(result.getErrorMessage())) {
                builder.append(" 错误：")
                        .append(truncate(result.getErrorMessage(), 160));
            }
            builder.append('\n');
        }

        String text = builder.toString().strip();
        return hasText(text) ? text : "暂无分支 Agent 结果。";
    }

    /**
     * 限制注入 Prompt 的工具原始数据长度。
     */
    private static String truncate(String value, int maxLength) {
        if (!hasText(value) || value.length() <= maxLength) {
            return value;
        }
        return value.substring(0, maxLength) + "...";
    }

    /**
     * 将模型原始输出解析为 PlannerDraft。
     *
     * <p>解析策略：
     * <ol>
     *   <li>先剥离可能出现的 Markdown 代码块。</li>
     *   <li>优先按完整 JSON Object 解析。</li>
     *   <li>如果模型在 JSON 外混入解释文字，则尝试截取首尾大括号中的 JSON。</li>
     *   <li>仍失败时，将原文作为 Markdown 行程草案，避免流程中断。</li>
     * </ol>
     * </p>
     *
     * @param rawResponse 模型原始输出
     * @return 解析或降级后的 PlannerDraft
     */
    PlannerDraft parseOrFallback(String rawResponse) {
        String cleaned = stripMarkdownFences(rawResponse);
        if (!hasText(cleaned)) {
            return buildFallbackDraft("模型返回为空，暂时无法生成完整规划草案。");
        }

        try {
            return objectMapper.readValue(cleaned, PlannerDraft.class);
        } catch (Exception first) {
            String jsonObject = extractJsonObject(cleaned);
            if (hasText(jsonObject)) {
                try {
                    return objectMapper.readValue(jsonObject, PlannerDraft.class);
                } catch (Exception ignored) {
                    log.warn("[Graph][PlanDraft] extracted JSON parse failed: {}", ignored.getMessage());
                }
            }
            PlannerDraft draft = buildFallbackDraft(cleaned);
            draft.setAssumptions(List.of("模型未返回合法 JSON，系统已按文本草案降级处理。"));
            return draft;
        }
    }

    /**
     * 移除模型可能包裹在 JSON 外的 Markdown 代码块标记。
     */
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

    /**
     * 从混杂文本中截取第一个 JSON Object。
     *
     * <p>模型偶发会输出“下面是 JSON：{...}”这样的文本，本方法用于温和修复。</p>
     */
    private static String extractJsonObject(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start >= 0 && end > start) {
            return text.substring(start, end + 1);
        }
        return "";
    }

    /**
     * 构造兜底草案。
     *
     * <p>兜底草案保留可展示文本，并补充预算与风险提醒，保证 Finalizer 仍能输出完整结构。</p>
     */
    private static PlannerDraft buildFallbackDraft(String content) {
        PlannerDraft draft = new PlannerDraft();
        draft.setTitle("欧洲旅行规划草案");
        draft.setSummary("根据当前信息生成一版初步规划草案。");
        draft.setItineraryMarkdown(defaultText(content, "暂时无法生成完整分天行程。"));
        draft.setBudgetNotes("预算需要结合实时航班、住宿和门票价格进一步确认。");
        draft.setRiskNotes("建议在出行前复核交通、预约、天气和当地政策等信息。");
        draft.setAssumptions(new ArrayList<>());
        return draft;
    }

    /**
     * 补齐模型草案中的空字段。
     *
     * <p>大模型可能返回缺字段或空字符串。这里统一补默认值，降低 Validator / Finalizer 的空值处理压力。</p>
     */
    private static void normalizeDraft(PlannerDraft draft, TravelPlanState state) {
        if (draft == null) {
            return;
        }
        if (!hasText(draft.getTitle())) {
            draft.setTitle("欧洲旅行规划草案");
        }
        if (!hasText(draft.getSummary())) {
            draft.setSummary("根据您当前提供的信息，先生成一版可继续细化的旅行规划。");
        }
        if (!hasText(draft.getItineraryMarkdown())) {
            draft.setItineraryMarkdown("暂时缺少足够信息生成完整分天行程，请补充旅行天数、日期或偏好。");
        }
        if (!hasText(draft.getBudgetNotes())) {
            draft.setBudgetNotes("预算需要结合实时航班、住宿、餐饮和门票价格进一步确认。");
        }
        if (!hasText(draft.getRiskNotes())) {
            draft.setRiskNotes("请在出行前复核交通班次、景点预约、天气和当地安全提醒。");
        }
        if (draft.getAssumptions() == null) {
            draft.setAssumptions(new ArrayList<>());
        }
        if (state != null && (!hasText(state.getTravelTime()) || "未指定".equals(state.getTravelTime()))) {
            draft.getAssumptions().add("用户尚未提供明确出行时间，行程日期需要后续确认。");
        }
        if (state != null && state.getDurationDays() == null) {
            draft.getAssumptions().add("用户尚未提供明确行程天数，本次行程长度需要后续确认。");
        }
    }

    /**
     * 返回非空文本，否则返回兜底文本。
     */
    private static String defaultText(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    /**
     * 判断字符串是否包含非空白文本。
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
