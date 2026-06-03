package com.travel.agent.ai.graph.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.ai.AiModelBeanNames;
import com.travel.agent.ai.graph.model.BranchResult;
import com.travel.agent.ai.graph.model.PlannerDraft;
import com.travel.agent.ai.graph.model.RiskAssessment;
import com.travel.agent.ai.graph.model.RiskIssue;
import com.travel.agent.ai.graph.model.RiskIssueType;
import com.travel.agent.ai.graph.model.RiskSeverity;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.ValidationIssue;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/**
 * 行程风险推理节点（Graph 层 - 输出前审查器）。
 *
 * <p>系统架构位置：ValidateDraftNode -> <b>TripRiskReasoningNode</b> -> PlanRevisionNode / FinalizeAnswerNode</p>
 *
 * <p>职责：
 * <ul>
 *   <li>读取 {@link TravelPlanState} 中的用户需求、{@link PlannerDraft}、Validator 问题、RAG 上下文和分支工具结果。</li>
 *   <li>在最终输出前审查草案是否满足用户约束、RAG 上下文和分支工具事实。</li>
 *   <li>输出结构化 {@link RiskAssessment}，并写回 {@link TravelPlanState#setRiskAssessment(RiskAssessment)}。</li>
 *   <li>由 Facade 根据 riskAssessment 决定是否进入 PlanRevisionNode、ClarifyQuestionNode 或 FinalizeAnswerNode。</li>
 *   <li>只输出审查结论，不输出模型隐藏思维链。</li>
 *   <li>模型审查失败时回退到确定性 Java 规则，保证主流程不被打断。</li>
 * </ul>
 * </p>
 *
 * <p>设计边界：本节点负责“发现问题并给出结构化修正建议”，不直接改写草案；
 * 真正重写由 PlanRevisionNode 完成，避免审查和生成职责混在一起。</p>
 */
@Component
public class TripRiskReasoningNode {

    private static final Logger log = LoggerFactory.getLogger(TripRiskReasoningNode.class);

    /** 用于识别中文 Markdown 行程中的“第1天 / 第 2 天”等天数标记。 */
    private static final Pattern CHINESE_DAY_PATTERN = Pattern.compile("第\\s*(\\d{1,2})\\s*天");

    /** 用于识别英文 Markdown 行程中的 “Day 1 / day2” 等天数标记。 */
    private static final Pattern ENGLISH_DAY_PATTERN = Pattern.compile("(?i)\\bday\\s*(\\d{1,2})\\b");

    /** DeepSeek Pro 对应的 ChatClient，用于复杂语义风险审查。 */
    private final ChatClient coreChatClient;

    /** 解析模型 JSON 输出为 RiskAssessment 的 Jackson 工具。 */
    private final ObjectMapper objectMapper;

    /**
     * 构造器注入核心模型和 JSON 解析器。
     *
     * <p>风险审查使用核心模型做结构化判断，但只要求返回 JSON 结论，不要求输出推理过程。
     * Spring 生产环境使用本构造器；单元测试可通过包内构造器传入 null ChatClient，只验证 Java 规则。</p>
     *
     * @param coreChatModel DeepSeek Pro 模型 Bean
     * @param objectMapper  JSON 解析器
     */
    @Autowired
    public TripRiskReasoningNode(@Qualifier(AiModelBeanNames.CORE_CHAT_MODEL) ChatModel coreChatModel,
                                 ObjectMapper objectMapper) {
        this(ChatClient.create(coreChatModel), objectMapper);
    }

    /**
     * 包内测试构造器。
     *
     * <p>测试中可传入 null 跳过模型审查，或覆写 {@link #callModel(String, String)} 验证模型边界。</p>
     */
    TripRiskReasoningNode(ChatClient coreChatClient, ObjectMapper objectMapper) {
        this.coreChatClient = coreChatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 执行行程风险审查。
     *
     * <p>处理流程：</p>
     * <ol>
     *   <li>先用确定性 Java 规则找出可稳定判断的问题。</li>
     *   <li>如果可用，调用核心模型做一次结构化审查。</li>
     *   <li>合并规则和模型结果，写回 TravelPlanState.riskAssessment。</li>
     * </ol>
     *
     * <p>异常策略：模型审查失败时只记录 warn 日志，并保留 Java 规则审查结果；
     * 不向上传播异常，避免输出前审查节点反过来破坏主规划流程。</p>
     *
     * @param state 当前旅行规划状态
     * @return 写入 riskAssessment 后的状态
     */
    public TravelPlanState assess(TravelPlanState state) {
        if (state == null) {
            TravelPlanState fallback = new TravelPlanState();
            fallback.setRiskAssessment(RiskAssessment.clear());
            return fallback;
        }

        RiskAssessment ruleAssessment = assessByRules(state);
        RiskAssessment modelAssessment = RiskAssessment.clear();
        if (coreChatClient != null && state.getDraft() != null) {
            modelAssessment = assessByModelOrClear(state);
        }

        RiskAssessment merged = mergeAssessments(ruleAssessment, modelAssessment);
        state.setRiskAssessment(merged);
        log.info("[Graph][RiskReasoning] issues={}, needsRevision={}, needsClarification={}",
                merged.getIssues().size(),
                merged.isNeedsRevision(),
                merged.isNeedsClarification());
        return state;
    }

    /**
     * 确定性规则审查。
     *
     * <p>读取 state 中的用户文本、关键词、目的地、duration、draft、branchResults 和 validationIssues。
     * 这些规则覆盖天数不匹配、目的地遗漏、预算口径冲突、避人流偏好冲突和工具失败等稳定场景。</p>
     *
     * @param state 当前旅行规划状态
     * @return 只由 Java 规则生成的风险审查结果
     */
    RiskAssessment assessByRules(TravelPlanState state) {
        List<RiskIssue> issues = new ArrayList<>();
        PlannerDraft draft = state.getDraft();
        String fullDraftText = fullDraftText(draft);
        String userText = defaultText(state.getUserQuery(), "") + " "
                + String.join(" ", state.getKeywords() == null ? List.of() : state.getKeywords());

        addDurationMismatchIssue(state, fullDraftText, issues);
        addDestinationMismatchIssues(state, fullDraftText, issues);
        addCrowdConflictIssue(userText, fullDraftText, issues);
        addFlightBudgetConflictIssue(userText, draft, issues);
        addToolUnavailableIssues(state.getBranchResults(), fullDraftText, issues);
        addValidationWarnings(state.getValidationIssues(), issues);

        return buildAssessment(issues);
    }

    /**
     * 调用核心模型做结构化风险审查。
     *
     * <p>模型负责补充 Java 规则不容易覆盖的语义问题，例如行程强度、隐含交通风险等。
     * 如果模型调用或 JSON 解析失败，本方法返回 clear assessment，让规则结果继续生效。</p>
     */
    private RiskAssessment assessByModelOrClear(TravelPlanState state) {
        try {
            String raw = callModel(buildSystemPrompt(state), state.getUserQuery());
            return normalizeAssessment(parseAssessment(raw));
        } catch (Exception e) {
            log.warn("[Graph][RiskReasoning] model audit failed: {}", e.getMessage());
            return RiskAssessment.clear();
        }
    }

    /**
     * 调用核心模型。
     *
     * <p>拆成独立方法是为了测试时覆写模型调用边界。生产环境要求模型只返回 RiskAssessment JSON，
     * 不返回自然语言解释或隐藏推理过程。</p>
     *
     * @param systemPrompt 已注入草案、工具结果和校验问题的审查提示词
     * @param userQuery    用户原始需求
     * @return 模型原始输出；期望是 RiskAssessment JSON
     */
    protected String callModel(String systemPrompt, String userQuery) {
        return coreChatClient.prompt()
                .system(systemPrompt)
                .user(hasText(userQuery) ? userQuery : "请审查当前旅行规划草案。")
                .call()
                .content();
    }

    /**
     * 构建风险审查 Prompt。
     *
     * <p>提示词同时注入用户需求、已确认信息、Validator 问题、RAG 上下文、分支结果和当前草案。
     * 它只要求输出 JSON 风险结论，不允许输出内部推理过程。</p>
     *
     * @param state 当前旅行规划状态
     * @return 给核心模型使用的完整风险审查提示词
     */
    String buildSystemPrompt(TravelPlanState state) {
        return """
                你是旅行 Agent 工作流中的 TripRiskReasoningNode。请审查旅行规划草案是否满足用户约束、RAG 上下文和分支工具结果。

                严格要求：
                1. 只输出合法 JSON Object，不要输出 Markdown，不要输出解释文字。
                2. 不要输出你的隐藏推理过程，只输出结构化结论。
                3. 只标记会影响旅行质量或用户约束的问题。
                4. 可自动修正的问题设置 autoRevisable=true。
                5. 必须用户补充的问题设置 requiresClarification=true。
                6. type 只能使用这些枚举：WEATHER_CONFLICT, CROWD_CONFLICT, BUDGET_CONFLICT, DURATION_MISMATCH, DESTINATION_MISMATCH, FLIGHT_BUDGET_CONFLICT, TRANSPORT_RISK, OVERLOADED_DAY, RAG_WARNING, TOOL_UNAVAILABLE, OPERATING_HOURS, BOOKING_REQUIRED, UNKNOWN。
                7. 开放时间、闭馆日、营业时段与行程安排冲突时使用 OPERATING_HOURS；必须预约或固定入场时段风险使用 BOOKING_REQUIRED；类型不确定时使用 UNKNOWN。
                8. 当前系统日期：%s。

                用户需求：
                %s

                已确认信息：
                - 目的地：%s
                - 出行时间：%s
                - 行程时长：%s
                - 关键词：%s

                Validator 问题：
                %s

                RAG 上下文：
                %s

                分支 Agent 结果：
                %s

                当前草案：
                %s

                输出 JSON Schema：
                {
                  "needsRevision": true,
                  "needsClarification": false,
                  "issues": [
                    {
                      "type": "CROWD_CONFLICT",
                      "severity": "HIGH",
                      "code": "CROWD_CONFLICT",
                      "day": "第2天",
                      "message": "string",
                      "evidence": "string",
                      "suggestedAction": "string",
                      "autoRevisable": true,
                      "requiresClarification": false
                    }
                  ],
                  "revisionInstruction": "string"
                }
                """.formatted(
                LocalDate.now(),
                defaultText(state.getUserQuery(), "未提供"),
                state.getDestinations() == null || state.getDestinations().isEmpty()
                        ? "未指定"
                        : String.join("、", state.getDestinations()),
                defaultText(state.getTravelTime(), "未指定"),
                defaultText(state.getDurationText(), "未指定"),
                state.getKeywords() == null || state.getKeywords().isEmpty()
                        ? "无"
                        : String.join("、", state.getKeywords()),
                formatValidationIssues(state.getValidationIssues()),
                defaultText(state.getRagContext(), "无"),
                formatBranchResults(state.getBranchResults()),
                fullDraftText(state.getDraft())
        );
    }

    /**
     * 将模型 JSON 输出解析为 RiskAssessment。
     *
     * <p>解析策略：</p>
     * <ol>
     *   <li>先移除模型可能包裹在 JSON 外的 Markdown 代码块。</li>
     *   <li>优先按完整 JSON Object 解析。</li>
     *   <li>如果模型混入解释文字，则尝试截取首尾大括号中的 JSON。</li>
     *   <li>仍失败时抛出异常，由上层 {@link #assessByModelOrClear(TravelPlanState)} 降级为空模型审查结果。</li>
     * </ol>
     *
     * @param rawResponse 模型原始输出
     * @return 解析后的风险审查结果
     * @throws Exception 当模型没有返回可解析 JSON 时抛出，由调用方兜底
     */
    RiskAssessment parseAssessment(String rawResponse) throws Exception {
        String cleaned = stripMarkdownFences(rawResponse);
        if (!hasText(cleaned)) {
            return RiskAssessment.clear();
        }
        try {
            return objectMapper.readValue(cleaned, RiskAssessment.class);
        } catch (Exception first) {
            String jsonObject = extractJsonObject(cleaned);
            if (hasText(jsonObject)) {
                return objectMapper.readValue(jsonObject, RiskAssessment.class);
            }
            throw first;
        }
    }

    /**
     * 检查草案天数是否匹配用户给出的 duration。
     *
     * <p>这是第四阶段最稳定的自动修正规则之一：用户说 10 天，草案却只有 8 天时，
     * 不需要追问用户，应该直接要求 PlanRevisionNode 重写成 10 天。</p>
     */
    private static void addDurationMismatchIssue(TravelPlanState state, String fullDraftText, List<RiskIssue> issues) {
        if (state.getDurationDays() == null || !hasText(fullDraftText)) {
            return;
        }
        int dayCount = countItineraryDays(fullDraftText);
        if (dayCount > 0 && dayCount != state.getDurationDays()) {
            issues.add(RiskIssue.autoRevisable(
                    RiskIssueType.DURATION_MISMATCH,
                    RiskSeverity.HIGH,
                    "DURATION_MISMATCH",
                    "用户要求 " + state.getDurationText() + "，但草案看起来是 " + dayCount + " 天。",
                    "durationDays=" + state.getDurationDays() + ", draftDays=" + dayCount,
                    "请重写行程，使推荐行程严格匹配用户指定的 " + state.getDurationText() + "。"));
        }
    }

    /**
     * 检查草案是否覆盖用户指定的全部目的地。
     *
     * <p>目的地遗漏通常可以自动修正，例如用户要求法国和意大利，但草案只写了法国。</p>
     */
    private static void addDestinationMismatchIssues(TravelPlanState state, String fullDraftText, List<RiskIssue> issues) {
        if (state.getDestinations() == null || state.getDestinations().isEmpty() || !hasText(fullDraftText)) {
            return;
        }
        for (String destination : state.getDestinations()) {
            if (hasText(destination) && !fullDraftText.contains(destination.trim())) {
                issues.add(RiskIssue.autoRevisable(
                        RiskIssueType.DESTINATION_MISMATCH,
                        RiskSeverity.HIGH,
                        "DESTINATION_MISMATCH",
                        "草案没有明确覆盖用户指定目的地：" + destination.trim() + "。",
                        "destinations=" + String.join("、", state.getDestinations()),
                        "请确保行程明确覆盖 " + destination.trim() + "，并说明停留天数或主要城市。"));
            }
        }
    }

    /**
     * 检查“避开人多 / 小众”偏好和热门景点堆叠之间的冲突。
     *
     * <p>第一版使用热门景点关键词计数做确定性规则；后续可升级为景点热度评分或分支 Agent 判断。</p>
     */
    private static void addCrowdConflictIssue(String userText, String fullDraftText, List<RiskIssue> issues) {
        if (!mentionsAvoidCrowds(userText) || !hasText(fullDraftText)) {
            return;
        }
        int popularCount = countPopularAttractions(fullDraftText);
        if (popularCount >= 4) {
            issues.add(RiskIssue.autoRevisable(
                    RiskIssueType.CROWD_CONFLICT,
                    RiskSeverity.HIGH,
                    "CROWD_CONFLICT",
                    "用户要求避开人多，但草案包含较多高人流热门景点。",
                    "热门景点命中数量=" + popularCount,
                    "请减少热门景点密度，把必要热门点改为清晨/夜间避峰或可选项，并增加小众街区、周边小镇和本地生活体验。"));
        }
    }

    /**
     * 检查预算口径是否和“不含国际机票”冲突。
     *
     * <p>这类问题不需要追问用户，因为用户已经明确说明预算边界，系统应自动修正预算说明。</p>
     */
    private static void addFlightBudgetConflictIssue(String userText, PlannerDraft draft, List<RiskIssue> issues) {
        if (!mentionsFlightExcluded(userText) || draft == null || !hasText(draft.getBudgetNotes())) {
            return;
        }
        String budgetText = draft.getBudgetNotes();
        if ((budgetText.contains("含国际机票") || budgetText.contains("包含国际机票")
                || budgetText.contains("包括国际机票"))
                && !budgetText.contains("不含国际机票")) {
            issues.add(RiskIssue.autoRevisable(
                    RiskIssueType.FLIGHT_BUDGET_CONFLICT,
                    RiskSeverity.HIGH,
                    "FLIGHT_BUDGET_CONFLICT",
                    "用户说明预算不含国际机票，但草案预算口径可能把国际机票计入。",
                    budgetText,
                    "请重写预算说明，明确 1200 欧不含国际机票，且不要把国际往返机票计入总预算。"));
        }
    }

    /**
     * 检查分支工具失败后，草案是否仍然引用了未确认的实时数据。
     *
     * <p>工具失败本身不是阻塞问题；真正需要修正的是“工具失败但草案还声称有实时价格、实时天气或实时航班”。</p>
     */
    private static void addToolUnavailableIssues(List<BranchResult> branchResults,
                                                 String fullDraftText,
                                                 List<RiskIssue> issues) {
        if (branchResults == null || branchResults.isEmpty()) {
            return;
        }
        for (BranchResult result : branchResults) {
            if (result == null || result.isSuccess()) {
                continue;
            }
            if (containsRealtimeClaim(fullDraftText)) {
                issues.add(RiskIssue.autoRevisable(
                        RiskIssueType.TOOL_UNAVAILABLE,
                        RiskSeverity.MEDIUM,
                        "TOOL_UNAVAILABLE",
                        result.getType() + " 分支不可用，但草案可能引用了实时数据。",
                        defaultText(result.getSummary(), result.getErrorMessage()),
                        "请移除未被工具确认的实时数据，改为提醒用户出发前复核。"));
            } else {
                issues.add(RiskIssue.warning(
                        RiskIssueType.TOOL_UNAVAILABLE,
                        RiskSeverity.LOW,
                        "TOOL_UNAVAILABLE",
                        result.getType() + " 分支没有返回可用结果，最终答案需要提示用户自行复核。",
                        defaultText(result.getSummary(), result.getErrorMessage())));
            }
        }
    }

    /**
     * 将 Validator 的非阻塞问题转为风险审查提示。
     *
     * <p>第四阶段第一版只把 RAG 不足映射为低风险提示；目的地缺失、目的地过宽等阻塞问题
     * 已经在 Facade 早期进入 ClarifyQuestionNode，不在这里重复处理。</p>
     */
    private static void addValidationWarnings(List<ValidationIssue> validationIssues, List<RiskIssue> issues) {
        if (validationIssues == null || validationIssues.isEmpty()) {
            return;
        }
        for (ValidationIssue issue : validationIssues) {
            if (issue == null || !hasText(issue.getCode())) {
                continue;
            }
            if ("INSUFFICIENT_RAG".equals(issue.getCode())) {
                issues.add(RiskIssue.warning(
                        RiskIssueType.RAG_WARNING,
                        RiskSeverity.LOW,
                        "RAG_WARNING",
                        issue.getMessage(),
                        "ValidateDraftNode"));
            }
        }
    }

    /**
     * 根据问题列表构造完整 RiskAssessment。
     *
     * <p>needsRevision 和 needsClarification 都从 RiskIssue 标记推导，避免调用方忘记同步布尔字段。</p>
     */
    private static RiskAssessment buildAssessment(List<RiskIssue> issues) {
        RiskAssessment assessment = new RiskAssessment();
        assessment.setIssues(issues);
        assessment.setNeedsRevision(issues.stream().anyMatch(RiskIssue::isAutoRevisable));
        assessment.setNeedsClarification(issues.stream().anyMatch(RiskIssue::isRequiresClarification));
        assessment.setRevisionInstruction(buildRevisionInstruction(issues));
        return assessment;
    }

    /**
     * 合并规则审查和模型审查结果。
     *
     * <p>规则审查负责稳定、可测试的问题；模型审查负责语义补充。合并时按 code/message 去重，
     * 避免同一问题在最终答案中重复出现。</p>
     */
    private static RiskAssessment mergeAssessments(RiskAssessment first, RiskAssessment second) {
        List<RiskIssue> mergedIssues = new ArrayList<>();
        Set<String> seenCodes = new LinkedHashSet<>();
        addUniqueIssues(mergedIssues, seenCodes, first == null ? null : first.getIssues());
        addUniqueIssues(mergedIssues, seenCodes, second == null ? null : second.getIssues());
        RiskAssessment merged = buildAssessment(mergedIssues);
        if (hasText(first == null ? null : first.getRevisionInstruction())
                && hasText(second == null ? null : second.getRevisionInstruction())) {
            merged.setRevisionInstruction(first.getRevisionInstruction() + "\n" + second.getRevisionInstruction());
        } else if (hasText(first == null ? null : first.getRevisionInstruction())) {
            merged.setRevisionInstruction(first.getRevisionInstruction());
        } else if (hasText(second == null ? null : second.getRevisionInstruction())) {
            merged.setRevisionInstruction(second.getRevisionInstruction());
        }
        return normalizeAssessment(merged);
    }

    /**
     * 将 source 中的问题去重后追加到 target。
     */
    private static void addUniqueIssues(List<RiskIssue> target, Set<String> seenCodes, List<RiskIssue> source) {
        if (source == null || source.isEmpty()) {
            return;
        }
        for (RiskIssue issue : source) {
            if (issue == null) {
                continue;
            }
            String key = defaultText(issue.getCode(), String.valueOf(issue.getType())) + "|"
                    + defaultText(issue.getMessage(), "");
            if (seenCodes.add(key)) {
                target.add(issue);
            }
        }
    }

    /**
     * 规范化风险审查结果。
     *
     * <p>模型可能漏填 needsRevision、needsClarification 或 revisionInstruction。
     * 这里根据 issues 重新推导关键字段，保证 Facade 可以稳定判断下一步流向。</p>
     */
    private static RiskAssessment normalizeAssessment(RiskAssessment assessment) {
        if (assessment == null) {
            return RiskAssessment.clear();
        }
        if (assessment.getIssues() == null) {
            assessment.setIssues(new ArrayList<>());
        }
        sanitizeClarificationFlags(assessment.getIssues());
        assessment.setNeedsRevision(assessment.isNeedsRevision()
                || assessment.getIssues().stream().anyMatch(RiskIssue::isAutoRevisable));
        assessment.setNeedsClarification(assessment.getIssues().stream().anyMatch(RiskIssue::isRequiresClarification));
        if (!hasText(assessment.getRevisionInstruction())) {
            assessment.setRevisionInstruction(buildRevisionInstruction(assessment.getIssues()));
        }
        return assessment;
    }

    /**
     * 收紧模型审查触发澄清的权限。
     *
     * <p>第四阶段的目标是“能自动修则自动修”。模型偶尔会把避峰、预算口径或工具失败这类可修正问题
     * 标成 requiresClarification=true，导致流程过早追问用户。这里只允许真正缺少关键信息的问题进入澄清，
     * 其余风险统一交给 PlanRevisionNode 或 Finalizer 处理。</p>
     */
    private static void sanitizeClarificationFlags(List<RiskIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return;
        }
        for (RiskIssue issue : issues) {
            if (issue == null || !issue.isRequiresClarification()) {
                continue;
            }
            if (!isClarificationAllowed(issue)) {
                issue.setRequiresClarification(false);
            }
        }
    }

    /**
     * 判断某个风险问题是否真的需要追问用户。
     */
    private static boolean isClarificationAllowed(RiskIssue issue) {
        String code = issue == null ? null : issue.getCode();
        if (!hasText(code)) {
            return false;
        }
        return "MISSING_DESTINATION".equals(code)
                || "BROAD_DESTINATION".equals(code)
                || "MISSING_DATE".equals(code)
                || "MISSING_DURATION".equals(code)
                || "MISSING_BUDGET".equals(code);
    }

    /**
     * 汇总所有可自动修正问题的 suggestedAction。
     *
     * <p>PlanRevisionNode 会把这段文本作为模型重写草案的核心指令。</p>
     */
    private static String buildRevisionInstruction(List<RiskIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return null;
        }
        List<String> actions = issues.stream()
                .filter(RiskIssue::isAutoRevisable)
                .map(RiskIssue::getSuggestedAction)
                .filter(TripRiskReasoningNode::hasText)
                .collect(Collectors.toList());
        if (actions.isEmpty()) {
            return null;
        }
        return String.join("\n", actions);
    }

    /**
     * 统计草案中出现的行程天数标记数量。
     *
     * <p>支持中文“第1天”和英文“Day 1”。这只是启发式检查，用于发现明显天数不匹配。</p>
     */
    private static int countItineraryDays(String text) {
        Set<Integer> days = new LinkedHashSet<>();
        Matcher chineseMatcher = CHINESE_DAY_PATTERN.matcher(text);
        while (chineseMatcher.find()) {
            days.add(Integer.parseInt(chineseMatcher.group(1)));
        }
        Matcher englishMatcher = ENGLISH_DAY_PATTERN.matcher(text);
        while (englishMatcher.find()) {
            days.add(Integer.parseInt(englishMatcher.group(1)));
        }
        return days.size();
    }

    /**
     * 统计草案命中的高人流热门景点数量。
     *
     * <p>第一版使用硬编码关键词，目标是快速捕获“用户要小众但草案堆热门”的明显冲突。
     * 后续可以替换为景点热度分支或数据库评分。</p>
     */
    private static int countPopularAttractions(String text) {
        String[] popular = {
                "卢浮宫", "埃菲尔铁塔", "巴黎圣母院", "凯旋门", "香榭丽舍",
                "凡尔赛", "许愿池", "斗兽场", "古罗马广场", "梵蒂冈",
                "西班牙广场", "纳沃纳广场", "朱丽叶阳台", "米兰大教堂",
                "圣马可广场", "比萨斜塔", "乌菲兹", "圣母百花大教堂"
        };
        int count = 0;
        for (String keyword : popular) {
            if (text.contains(keyword)) {
                count++;
            }
        }
        return count;
    }

    /**
     * 判断用户是否表达了避开人流或小众偏好。
     */
    private static boolean mentionsAvoidCrowds(String text) {
        return hasText(text)
                && (text.contains("避开人多")
                || text.contains("避开人流")
                || text.contains("少去网红")
                || text.contains("小众")
                || text.contains("冷门")
                || text.toLowerCase(Locale.ROOT).contains("avoid crowds"));
    }

    /**
     * 判断用户是否明确说明预算不含国际机票。
     */
    private static boolean mentionsFlightExcluded(String text) {
        return hasText(text)
                && (text.contains("不含国际机票")
                || text.contains("不含机票")
                || text.contains("不包括国际机票")
                || text.contains("不包含国际机票")
                || text.contains("机票自理"));
    }

    /**
     * 判断草案是否声称使用了实时数据。
     *
     * <p>当对应分支工具失败时，实时数据声明需要自动修正为“出发前复核”。</p>
     */
    private static boolean containsRealtimeClaim(String text) {
        return hasText(text)
                && (text.contains("实时")
                || text.contains("当前")
                || text.contains("现在")
                || text.contains("今日")
                || text.toLowerCase(Locale.ROOT).contains("real-time"));
    }

    /**
     * 将 PlannerDraft 合并为审查用全文。
     *
     * <p>风险规则需要跨标题、行程、预算、风险和假设一起判断，因此这里统一拼接为一段文本。</p>
     */
    private static String fullDraftText(PlannerDraft draft) {
        if (draft == null) {
            return "";
        }
        return String.join("\n",
                defaultText(draft.getTitle(), ""),
                defaultText(draft.getSummary(), ""),
                defaultText(draft.getItineraryMarkdown(), ""),
                defaultText(draft.getBudgetNotes(), ""),
                defaultText(draft.getRiskNotes(), ""),
                draft.getAssumptions() == null ? "" : String.join("\n", draft.getAssumptions()));
    }

    /**
     * 将 Validator 问题压缩为 Prompt 文本。
     */
    private static String formatValidationIssues(List<ValidationIssue> issues) {
        if (issues == null || issues.isEmpty()) {
            return "无";
        }
        return issues.stream()
                .filter(issue -> issue != null)
                .map(issue -> "- " + defaultText(issue.getCode(), "UNKNOWN") + ": "
                        + defaultText(issue.getMessage(), "无说明"))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 将分支 Agent 结果压缩为 Prompt 文本。
     *
     * <p>只传递成功/失败和摘要，避免把过长 rawData 注入风险审查 Prompt。</p>
     */
    private static String formatBranchResults(List<BranchResult> branchResults) {
        if (branchResults == null || branchResults.isEmpty()) {
            return "无";
        }
        return branchResults.stream()
                .filter(result -> result != null)
                .map(result -> "- " + result.getType() + (result.isSuccess() ? " [SUCCESS] " : " [FAILED] ")
                        + defaultText(result.getSummary(), "无摘要"))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 移除模型可能包裹在 JSON 外层的 Markdown 代码块标记。
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
     * 返回非空文本，否则返回兜底文本。
     */
    private static String defaultText(String value, String fallback) {
        return hasText(value) ? value.trim() : fallback;
    }

    /**
     * 判断字符串是否包含非空白文本。
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
