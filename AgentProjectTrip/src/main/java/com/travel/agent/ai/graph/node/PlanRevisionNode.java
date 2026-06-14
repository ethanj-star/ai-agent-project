package com.travel.agent.ai.graph.node;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.ai.AiModelBeanNames;
import com.travel.agent.ai.graph.model.PlannerDraft;
import com.travel.agent.ai.graph.model.RiskAssessment;
import com.travel.agent.ai.graph.model.RiskIssue;
import com.travel.agent.ai.graph.model.TravelPlanState;
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
import java.util.stream.Collectors;

/**
 * 规划草案自动修正节点（Graph 层 - Revision 修正器）。
 *
 * <p>系统架构位置：TripRiskReasoningNode -> <b>PlanRevisionNode</b> -> ValidateDraftNode</p>
 *
 * <p>职责：
 * <ul>
 *   <li>读取 {@link TravelPlanState#getRiskAssessment()} 中的可自动修正问题。</li>
 *   <li>读取当前 {@link TravelPlanState#getDraft()}、用户原始需求、目的地、时长和关键词，构造修正 Prompt。</li>
 *   <li>调用核心模型生成新的 {@link PlannerDraft}，并写回 {@link TravelPlanState#setDraft(PlannerDraft)}。</li>
 *   <li>每次执行都会增加 {@link TravelPlanState#getRevisionCount()}，配合 Facade 的 maxRevisionCount 防止修正循环失控。</li>
 *   <li>模型调用失败或 JSON 解析失败时保留原草案，并把降级信息写入 assumptions，不打断主流程。</li>
 * </ul>
 * </p>
 *
 * <p>设计边界：本节点只负责“根据风险审查结果修正草案”，不重新派发工具、不重新做 RAG；
 * 第四阶段第一版由 {@code LangGraphPlannerFacade} 控制最多自动修正一次。</p>
 */
@Component
public class PlanRevisionNode {

    private static final Logger log = LoggerFactory.getLogger(PlanRevisionNode.class);

    /** DeepSeek Pro 对应的 ChatClient，仅用于自动修正 PlannerDraft。 */
    private final ChatClient coreChatClient;

    /** 解析模型 JSON 输出为 PlannerDraft 的 Jackson 工具。 */
    private final ObjectMapper objectMapper;

    /**
     * 构造器注入核心模型和 JSON 解析器。
     *
     * <p>这里显式注入 {@code coreChatModel}，因为修正草案需要和 Planner 使用同一级别的推理能力。
     * Spring 生产环境使用本构造器；单元测试使用包内构造器替换 ChatClient，避免访问真实模型。</p>
     *
     * @param coreChatModel DeepSeek Pro 模型 Bean
     * @param objectMapper  JSON 解析器
     */
    @Autowired
    public PlanRevisionNode(@Qualifier(AiModelBeanNames.CORE_CHAT_MODEL) ChatModel coreChatModel,
                            ObjectMapper objectMapper) {
        this(ChatClient.create(coreChatModel), objectMapper);
    }

    /**
     * 包内测试构造器。
     *
     * <p>测试中可传入 null 或覆写 {@link #callModel(String, String)}，只验证 Prompt、解析和状态写回逻辑。</p>
     */
    PlanRevisionNode(ChatClient coreChatClient, ObjectMapper objectMapper) {
        this.coreChatClient = coreChatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据风险审查结果重写规划草案。
     *
     * <p>处理流程：</p>
     * <ol>
     *   <li>检查状态、草案和风险审查结果是否存在可自动修正问题；没有则原样返回。</li>
     *   <li>将 revisionCount 加一，记录本轮自动修正已经发生。</li>
     *   <li>构造修正 Prompt，注入用户硬约束、当前草案和风险修正指令。</li>
     *   <li>调用核心模型，要求只返回 PlannerDraft JSON。</li>
     *   <li>解析成功时写回新版 draft；解析失败时保留原 draft 并写入 assumptions。</li>
     * </ol>
     *
     * <p>异常策略：模型调用、网络错误或 JSON 解析异常都不会继续向上抛出；
     * 本节点会保留原草案，让后续 Finalizer 仍能输出可读方案。</p>
     *
     * @param state 当前旅行规划状态
     * @return 写入新版 draft 并增加 revisionCount 后的状态
     */
    public TravelPlanState revise(TravelPlanState state) {
        if (state == null) {
            return new TravelPlanState();
        }
        // 没有可自动修正风险，或当前没有草案时，不做任何重写。
        if (!hasRevisableRisk(state.getRiskAssessment()) || state.getDraft() == null) {
            return state;
        }

        // 先增加计数，Facade 会用 revisionCount / maxRevisionCount 防止自动修正循环失控。
        state.setRevisionCount(state.getRevisionCount() + 1);
        String systemPrompt = buildRevisionPrompt(state);
        log.info("[Graph][PlanRevision] revising draft, revisionCount={}", state.getRevisionCount());

        try {
            // 修正模型仍然返回 PlannerDraft JSON；解析失败时保留旧草案。
            String rawResponse = callModel(systemPrompt, state.getUserQuery());
            PlannerDraft revisedDraft = parseOrFallback(rawResponse, state.getDraft());
            normalizeDraft(revisedDraft, state);
            state.setDraft(revisedDraft);
            return state;
        } catch (Exception e) {
            // 自动修正失败不是致命错误，保留原方案并把风险留给最终答案提示用户。
            log.warn("[Graph][PlanRevision] revision failed: {}", e.getMessage());
            appendAssumption(state.getDraft(), "系统尝试自动修正风险问题，但修正模型暂时不可用，以下方案仍需人工复核。");
            return state;
        }
    }

    /**
     * 调用核心模型。
     *
     * <p>拆成独立方法是为了测试时覆写模型调用边界。生产环境通过 Spring AI ChatClient
     * 将修正 Prompt 和用户原始需求发送给核心模型。</p>
     *
     * @param systemPrompt 已注入风险审查和当前草案的系统提示词
     * @param userQuery    用户原始需求
     * @return 模型返回的原始文本；期望是 PlannerDraft JSON
     */
    protected String callModel(String systemPrompt, String userQuery) {
        return coreChatClient.prompt()
                .system(systemPrompt)
                .user(hasText(userQuery) ? userQuery : "请根据风险审查结果修正旅行规划草案。")
                .call()
                .content();
    }

    /**
     * 构造修正 Prompt。
     *
     * <p>Prompt 明确要求模型只输出 PlannerDraft JSON，并且不得破坏用户原始硬约束。
     * 这里会注入 riskAssessment 中的问题和 revisionInstruction，使模型只围绕审查问题修正，
     * 避免无关重写造成路线漂移。</p>
     *
     * @param state 当前旅行规划状态，必须包含 draft 和 riskAssessment
     * @return 给核心模型使用的完整修正提示词
     */
    String buildRevisionPrompt(TravelPlanState state) {
        PlannerDraft draft = state.getDraft();
        RiskAssessment assessment = state.getRiskAssessment();
        return """
                你是旅行 Agent 工作流中的 PlanRevisionNode。请根据风险审查结果修正当前旅行规划草案。

                严格要求：
                1. 只输出合法 JSON Object，不要输出 Markdown 代码块，不要输出解释文字。
                2. 不要输出隐藏推理过程。
                3. 必须保留用户指定的目的地、预算口径、出行时间和行程时长。
                4. 如果用户说不含国际机票，预算中不得把国际往返机票计入总额。
                5. 如果用户要求避开人多，热门景点只能作为可选项或避峰时段，不要作为主轴堆叠。
                6. 如果工具失败或未提供实时数据，不要伪造实时价格、实时航班或实时天气。
                7. 当前系统日期：%s。

                用户原始需求：
                %s

                已确认信息：
                - 目的地：%s
                - 出行时间：%s
                - 行程时长：%s
                - 关键词：%s

                风险审查问题：
                %s

                修正指令：
                %s

                当前草案：
                标题：%s
                总体思路：%s
                推荐行程：
                %s
                预算说明：
                %s
                风险提醒：
                %s
                当前假设：
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
                state.getDestinations() == null || state.getDestinations().isEmpty()
                        ? "未指定"
                        : String.join("、", state.getDestinations()),
                defaultText(state.getTravelTime(), "未指定"),
                defaultText(state.getDurationText(), "未指定"),
                state.getKeywords() == null || state.getKeywords().isEmpty()
                        ? "无"
                        : String.join("、", state.getKeywords()),
                formatRiskIssues(assessment),
                defaultText(assessment == null ? null : assessment.getRevisionInstruction(), "请修正上述可自动修正问题。"),
                defaultText(draft == null ? null : draft.getTitle(), ""),
                defaultText(draft == null ? null : draft.getSummary(), ""),
                defaultText(draft == null ? null : draft.getItineraryMarkdown(), ""),
                defaultText(draft == null ? null : draft.getBudgetNotes(), ""),
                defaultText(draft == null ? null : draft.getRiskNotes(), ""),
                draft == null || draft.getAssumptions() == null ? "无" : String.join("\n", draft.getAssumptions())
        );
    }

    /**
     * 解析修正模型返回的 PlannerDraft。
     *
     * <p>解析策略：</p>
     * <ol>
     *   <li>先移除模型可能包裹在 JSON 外的 Markdown 代码块。</li>
     *   <li>优先按完整 JSON Object 解析。</li>
     *   <li>如果模型混入解释文字，则尝试截取首尾大括号中的 JSON。</li>
     *   <li>仍失败时保留旧草案，并追加“自动修正模型未返回合法 JSON”的 assumptions。</li>
     * </ol>
     *
     * @param rawResponse   模型原始输出
     * @param fallbackDraft 解析失败时保留的旧草案
     * @return 新版草案；解析失败时返回 fallbackDraft
     */
    PlannerDraft parseOrFallback(String rawResponse, PlannerDraft fallbackDraft) {
        // 和 Planner 一样，Revision 也要兼容模型返回 Markdown fence 或解释文字。
        String cleaned = stripMarkdownFences(rawResponse);
        if (!hasText(cleaned)) {
            return fallbackDraft;
        }
        try {
            return objectMapper.readValue(cleaned, PlannerDraft.class);
        } catch (Exception first) {
            String jsonObject = extractJsonObject(cleaned);
            if (hasText(jsonObject)) {
                try {
                    return objectMapper.readValue(jsonObject, PlannerDraft.class);
                } catch (Exception ignored) {
                    log.warn("[Graph][PlanRevision] extracted JSON parse failed: {}", ignored.getMessage());
                }
            }
            // 解析失败时不丢旧草案，只追加一条 assumption 说明自动修正没有真正应用。
            appendAssumption(fallbackDraft, "自动修正模型未返回合法 JSON，系统保留原草案。");
            return fallbackDraft;
        }
    }

    /**
     * 判断风险审查结果中是否存在可自动修正问题。
     */
    private static boolean hasRevisableRisk(RiskAssessment assessment) {
        return assessment != null
                && assessment.getIssues() != null
                && assessment.getIssues().stream().anyMatch(RiskIssue::isAutoRevisable);
    }

    /**
     * 补齐修正模型可能漏掉的草案字段。
     *
     * <p>自动修正只应改进草案，不应因为模型漏字段导致后续 Finalizer 空指针或输出残缺。
     * 因此缺失字段优先回退到旧草案，最后再写入最小兜底文本。</p>
     */
    private static void normalizeDraft(PlannerDraft draft, TravelPlanState state) {
        if (draft == null) {
            return;
        }
        // 修正结果缺字段时回退到旧草案，保证自动修正不会让答案结构变残缺。
        if (!hasText(draft.getTitle())) {
            draft.setTitle("欧洲旅行规划修正版");
        }
        if (!hasText(draft.getSummary())) {
            draft.setSummary("已根据系统风险审查结果修正行程。");
        }
        if (!hasText(draft.getItineraryMarkdown())) {
            draft.setItineraryMarkdown(state.getDraft() == null ? "暂无可用行程。" : state.getDraft().getItineraryMarkdown());
        }
        if (!hasText(draft.getBudgetNotes())) {
            draft.setBudgetNotes(state.getDraft() == null ? "预算仍需复核。" : state.getDraft().getBudgetNotes());
        }
        if (!hasText(draft.getRiskNotes())) {
            draft.setRiskNotes(state.getDraft() == null ? "出发前请复核交通、预约和天气。" : state.getDraft().getRiskNotes());
        }
        if (draft.getAssumptions() == null) {
            draft.setAssumptions(new ArrayList<>());
        }
        draft.getAssumptions().add("系统已根据输出前风险审查结果自动修正过本方案。");
    }

    /**
     * 将风险审查问题压缩成适合 Prompt 使用的短文本。
     *
     * <p>只传递问题类型、等级、说明和修正建议，不输出模型审查时的隐藏推理过程。</p>
     */
    private static String formatRiskIssues(RiskAssessment assessment) {
        if (assessment == null || assessment.getIssues() == null || assessment.getIssues().isEmpty()) {
            return "无";
        }
        return assessment.getIssues().stream()
                .filter(issue -> issue != null)
                .map(issue -> "- " + issue.getType() + " [" + issue.getSeverity() + "] "
                        + defaultText(issue.getMessage(), "无说明")
                        + " 建议：" + defaultText(issue.getSuggestedAction(), "无"))
                .collect(Collectors.joining("\n"));
    }

    /**
     * 给草案追加系统级假设或降级说明。
     *
     * <p>用于记录自动修正失败、解析失败等不应中断流程但需要用户知道的边界情况。</p>
     */
    private static void appendAssumption(PlannerDraft draft, String assumption) {
        if (draft == null || !hasText(assumption)) {
            return;
        }
        List<String> assumptions = new ArrayList<>(draft.getAssumptions() == null ? List.of() : draft.getAssumptions());
        assumptions.add(assumption);
        draft.setAssumptions(assumptions);
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
