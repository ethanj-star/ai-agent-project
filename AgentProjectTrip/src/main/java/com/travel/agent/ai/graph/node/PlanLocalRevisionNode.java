package com.travel.agent.ai.graph.node;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.ai.AiModelBeanNames;
import com.travel.agent.ai.graph.model.PlanLocalRevisionResult;
import com.travel.agent.ai.graph.model.PlanModificationDecision;
import com.travel.agent.ai.graph.model.TravelPlanRecord;
import com.travel.agent.ai.graph.model.TravelPlanVersion;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

/**
 * 局部计划重写节点（Graph 层 - 第六阶段版本修改核心）。
 *
 * <p>系统架构位置：PlanController -> <b>PlanLocalRevisionNode</b> -> DeepSeek Pro</p>
 *
 * <p>职责：
 * <ul>
 *   <li>读取当前计划版本、结构化需求表和用户修改意图。</li>
 *   <li>调用核心模型仅针对目标日期或目标模块做局部重写。</li>
 *   <li>返回新答案和修改摘要；模型失败时返回失败结果，不新增版本。</li>
 * </ul>
 * </p>
 */
@Component
public class PlanLocalRevisionNode {

    private static final Logger log = LoggerFactory.getLogger(PlanLocalRevisionNode.class);

    /** 核心模型客户端，用于局部重写已有计划。 */
    private final ChatClient coreChatClient;

    /** JSON 解析器。 */
    private final ObjectMapper objectMapper;

    /**
     * 构造器注入核心模型和 Jackson。
     *
     * @param coreChatModel DeepSeek Pro 模型 Bean
     * @param objectMapper  JSON 解析器
     */
    @Autowired
    public PlanLocalRevisionNode(@Qualifier(AiModelBeanNames.CORE_CHAT_MODEL) ChatModel coreChatModel,
                                 ObjectMapper objectMapper) {
        this(ChatClient.create(coreChatModel), objectMapper);
    }

    /**
     * 包内测试构造器。
     */
    PlanLocalRevisionNode(ChatClient coreChatClient, ObjectMapper objectMapper) {
        this.coreChatClient = coreChatClient;
        this.objectMapper = objectMapper;
    }

    /**
     * 根据用户修改指令局部重写当前计划。
     *
     * <p>处理流程：
     * <ol>
     *   <li>读取当前版本 finalAnswer。</li>
     *   <li>构建局部 revision prompt，强调不得改变已确认核心需求。</li>
     *   <li>调用核心模型输出 JSON：answer + modificationSummary。</li>
     *   <li>解析失败或模型异常时返回失败结果，调用方不新增版本。</li>
     * </ol>
     * </p>
     *
     * @param record      当前计划主记录
     * @param decision    修改意图识别结果
     * @param instruction 用户原始修改指令
     * @return 局部修改结果
     */
    public PlanLocalRevisionResult revise(TravelPlanRecord record,
                                          PlanModificationDecision decision,
                                          String instruction) {
        if (record == null || record.current().isEmpty()) {
            return PlanLocalRevisionResult.failure("当前计划不存在，无法修改。");
        }
        TravelPlanVersion current = record.current().orElseThrow();
        if (!hasText(current.getFinalAnswer())) {
            return PlanLocalRevisionResult.failure("当前计划文本为空，无法修改。");
        }

        try {
            String raw = callModel(buildSystemPrompt(record, current, decision), instruction);
            return parseResult(raw, decision);
        } catch (Exception e) {
            log.error("[Graph][PlanLocalRevision] model call failed: {}", e.getMessage());
            return PlanLocalRevisionResult.failure(e.getMessage());
        }
    }

    /**
     * 调用核心模型。
     */
    protected String callModel(String systemPrompt, String userInstruction) {
        return coreChatClient.prompt()
                .system(systemPrompt)
                .user(hasText(userInstruction) ? userInstruction : "请根据修改要求局部调整当前旅行计划。")
                .call()
                .content();
    }

    /**
     * 构建局部重写系统提示词。
     */
    String buildSystemPrompt(TravelPlanRecord record,
                             TravelPlanVersion current,
                             PlanModificationDecision decision) {
        return """
                你是旅行计划局部修改节点。你不是从零规划，而是在已有计划基础上做最小必要修改。

                严格要求：
                1. 只输出合法 JSON Object，不要输出 Markdown 代码块。
                2. 保留用户没有要求修改的内容。
                3. 不得改变已确认需求表中的目的地、预算、天数、人数、出发城市等核心字段。
                4. 如果用户指定了某一天，只重点修改那一天。
                5. 输出必须包含完整可展示 answer，而不是只输出片段。

                已确认需求表摘要：
                - 目的地：%s
                - 出行时间：%s
                - 天数：%s
                - 预算：%s

                修改范围：
                - 目标日期：%s
                - 目标模块：%s
                - 修改摘要：%s

                当前计划版本 v%s：
                %s

                输出 JSON Schema：
                {
                  "answer": "修改后的完整 Markdown 计划",
                  "modificationSummary": "本次修改摘要"
                }
                """.formatted(
                record.getRequirementSpec() == null || record.getRequirementSpec().getDestinations().isEmpty()
                        ? "未指定"
                        : String.join("、", record.getRequirementSpec().getDestinations()),
                record.getRequirementSpec() == null ? "未指定" : defaultText(record.getRequirementSpec().getStartDateText(), "未指定"),
                record.getRequirementSpec() == null || record.getRequirementSpec().getDurationDays() == null
                        ? "未指定"
                        : record.getRequirementSpec().getDurationDays() + "天",
                record.getRequirementSpec() == null || record.getRequirementSpec().getBudgetAmount() == null
                        ? "未指定"
                        : record.getRequirementSpec().getBudgetAmount().stripTrailingZeros().toPlainString()
                        + defaultText(record.getRequirementSpec().getBudgetCurrency(), ""),
                decision == null ? "未指定" : defaultText(decision.getTargetDay(), "未指定"),
                decision == null || decision.getTargetSections() == null || decision.getTargetSections().isEmpty()
                        ? "itinerary"
                        : String.join("、", decision.getTargetSections()),
                decision == null ? "按用户指令局部修改。" : defaultText(decision.getInstructionSummary(), "按用户指令局部修改。"),
                current.getVersion(),
                current.getFinalAnswer()
        );
    }

    /**
     * 解析模型局部修改结果。
     */
    PlanLocalRevisionResult parseResult(String raw, PlanModificationDecision decision) {
        String cleaned = stripMarkdownFences(raw);
        if (!hasText(cleaned)) {
            return PlanLocalRevisionResult.failure("模型返回为空。");
        }
        try {
            return parseJsonResult(cleaned, decision);
        } catch (Exception first) {
            String json = extractJsonObject(cleaned);
            if (hasText(json) && !json.equals(cleaned)) {
                try {
                    return parseJsonResult(json, decision);
                } catch (Exception ignored) {
                    log.warn("[Graph][PlanLocalRevision] extracted JSON parse failed: {}", ignored.getMessage());
                }
            }
            return PlanLocalRevisionResult.failure("模型没有返回合法 JSON。");
        }
    }

    private PlanLocalRevisionResult parseJsonResult(String json, PlanModificationDecision decision) throws Exception {
        JsonNode root = objectMapper.readTree(json);
        String answer = text(root, "answer");
        if (!hasText(answer)) {
            return PlanLocalRevisionResult.failure("模型没有返回 answer 字段。");
        }
        String summary = text(root, "modificationSummary");
        return PlanLocalRevisionResult.success(
                answer,
                hasText(summary) ? summary : defaultText(decision == null ? null : decision.getInstructionSummary(), "已根据用户反馈局部修改计划。"));
    }

    private static String text(JsonNode root, String field) {
        JsonNode node = root == null ? null : root.get(field);
        return node == null || node.isNull() ? null : node.asText();
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

    private static String defaultText(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
