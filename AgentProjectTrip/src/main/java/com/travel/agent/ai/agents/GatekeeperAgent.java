package com.travel.agent.ai.agents;

import com.travel.agent.ai.AiModelBeanNames;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

/**
 * 前台路由闸门智能体（Gatekeeper Agent）
 *
 * <p>系统架构位置：<b>Web 层 → Gatekeeper → [MastermindAgent | CoreBrainAgent]</b>
 *
 * <p>职责：作为多智能体系统的第一道关卡。用户的每一条消息都必须先经过 Gatekeeper
 * 进行意图识别与实体提取，输出结构化的 JSON 路由指令，再由上层调度器根据
 * {@code intent} 字段决定将请求分发到哪个下游 Agent 或工具链。
 *
 * <p>为何专用独立模型：
 * <ul>
 *   <li>路由决策对速度极其敏感——使用 DeepSeek Flash 保证亚秒级响应。</li>
 *   <li>Temperature 强制设为 0.0——路由必须确定，绝对不允许随机输出。</li>
 *   <li>MaxTokens 限制在 512——闸门只输出 JSON 片段，无需长文本生成能力。</li>
 * </ul>
 *
 * <p>输出 JSON 结构（示例）：
 * <pre>{@code
 * {
 *   "intent": "PLAN_OR_RAG",
 *   "entities": {
 *     "locations": ["瑞士", "意大利"],
 *     "time": "国庆节",
 *     "keywords": ["10天", "避开人多", "行程规划"]
 *   }
 * }
 * }</pre>
 *
 * <p>意图枚举（按优先级从低到高）：
 * <ol>
 *   <li>{@code DIRECT_CHAT} — 闲聊、问候、与旅游无关</li>
 *   <li>{@code TOOL_WEATHER} — 明确的天气查询</li>
 *   <li>{@code TOOL_FLIGHT} — 明确的机票/航班查询</li>
 *   <li>{@code PLAN_OR_RAG} — 行程规划、攻略、景点、多国方案</li>
 * </ol>
 */
@Service
public class GatekeeperAgent {

    private static final Logger log = LoggerFactory.getLogger(GatekeeperAgent.class);

    /**
     * 闸门系统提示词（Gatekeeper System Prompt）
     *
     * <p>设计原则：
     * <ul>
     *   <li>角色定义简短有力：让模型清晰知道自己是"路由网关"而非"对话助手"。</li>
     *   <li>枚举值锁死：明确列出 4 个允许值，并用优先级规则消除边界歧义。</li>
     *   <li>Few-Shot 示例：4 条覆盖主要场景，包括一条多意图优先级示例。</li>
     *   <li>终止指令：明确禁止输出 Markdown 和思维链，防止模型"过度生成"。</li>
     * </ul>
     */
    private static final String GATEKEEPER_SYSTEM_PROMPT =
            "你是一个欧洲旅游管家系统的前台路由网关（Gatekeeper）。\n" +
                    "你的唯一任务是分析用户的输入，提取关键信息，并严格输出合法的 JSON 对象。不要输出任何 Markdown 标记（如 ```json），也不要包含任何解释性文本。\n\n" +
                    "【意图分类要求】\n" +
                    "必须且只能从以下 4 个枚举值中选择 `intent`。如果用户的提问包含多个意图，请按以下优先级选择最高的一个：PLAN_OR_RAG > TOOL_FLIGHT > TOOL_WEATHER > DIRECT_CHAT。\n" +
                    "1. DIRECT_CHAT：打招呼、基础闲聊、时间询问、或与旅游完全无关的话题。\n" +
                    "2. TOOL_WEATHER：明确询问某地的天气。\n" +
                    "3. TOOL_FLIGHT：明确询问机票、航班信息。\n" +
                    "4. PLAN_OR_RAG：涉及旅游攻略、行程排期、多国规划、经验避坑、景点介绍。\n\n" +
                    "【JSON 输出结构】\n" +
                    "你必须严格按照以下 JSON 格式输出：\n" +
                    "{\n" +
                    "  \"intent\": \"上述4个枚举值之一\",\n" +
                    "  \"entities\": {\n" +
                    "    \"locations\": [\"提取的国家或城市名称数组，若无则输出 []\"],\n" +
                    "    \"time\": \"用户提及的时间，若无则输出 null\",\n" +
                    "    \"keywords\": [\"其他提取的关键信息数组，若无则输出 []\"]\n" +
                    "  },\n" +
                    "  \"direct_reply\": \"如果是 DIRECT_CHAT，请你直接在这里生成礼貌、简短的回复文字给用户；如果是其他意图，这里必须填 null\"\n" +
                    "}\n\n" +
                    "【Few-Shot 示例】\n" +
                    "输入：你好，能告诉我明天去巴黎的机票怎么买吗？\n" +
                    "输出：{\"intent\": \"TOOL_FLIGHT\", \"entities\": {\"locations\": [\"巴黎\"], \"time\": \"明天\", \"keywords\": [\"机票\", \"购买\"]}, \"direct_reply\": null}\n\n" +
                    "输入：帮我规划一下国庆节去瑞士和意大利的10天行程，要避开人多的地方。\n" +
                    "输出：{\"intent\": \"PLAN_OR_RAG\", \"entities\": {\"locations\": [\"瑞士\", \"意大利\"], \"time\": \"国庆节\", \"keywords\": [\"10天\", \"避开人多\", \"行程规划\"]}, \"direct_reply\": null}\n\n" +
                    "输入：你叫什么名字？能做什么？\n" +
                    "输出：{\"intent\": \"DIRECT_CHAT\", \"entities\": {\"locations\": [], \"time\": \"现在\", \"keywords\": [\"名字\", \"时间\"]}, \"direct_reply\": \"你好，我是你的欧洲旅游百事通！我可以帮您查询机票，生成旅行攻略，查看景点信息和查询天气。\"}\n\n" +
                    "输入：伦敦明天下雨吗？对了，去伦敦办签证麻烦吗？\n" +
                    "输出：{\"intent\": \"PLAN_OR_RAG\", \"entities\": {\"locations\": [\"伦敦\"], \"time\": \"明天\", \"keywords\": [\"天气\", \"下雨\", \"签证\"]}, \"direct_reply\": null}\n\n" +
                    "不要输出任何多余的 Markdown 标记或思考过程。";

    /**
     * 降级兜底响应：当大模型调用失败时，默认将请求当作 DIRECT_CHAT 处理，
     * 避免因路由异常导致整个请求链路中断。
     */
    private static final String FALLBACK_RESPONSE =
            "{\"intent\":\"DIRECT_CHAT\"," +
            "\"entities\":{\"locations\":[],\"time\":null,\"keywords\":[]}}";

    /**
     * 基于 DeepSeek Flash 构建的 ChatClient，专用于路由判断。
     * 通过 {@link ChatClient#create(ChatModel)} 从注入的 ChatModel 直接构建，
     * 无需依赖 Spring 容器中的自动配置 Builder。
     */
    private final ChatClient chatClient;

    /**
     * 构造器注入 gatekeeperChatModel（DeepSeek Flash）。
     *
     * <p>{@link Qualifier} 精确指定使用 Engine 1（闸门模型），
     * 与系统默认的 {@link org.springframework.context.annotation.Primary}
     * branchChatModel 严格隔离。
     *
     * @param chatModel gatekeeperChatModel Bean（DeepSeek Flash，Temperature=0.0）
     */
    public GatekeeperAgent(@Qualifier(AiModelBeanNames.GATEKEEPER_CHAT_MODEL) ChatModel chatModel) {
        // 从指定的 ChatModel 实例构建独立的 ChatClient，
        // 避免与系统默认 ChatClient.Builder（branchChatModel）产生任何耦合
        this.chatClient = ChatClient.create(chatModel);
    }

    // ── 公开 API ───────────────────────────────────────────────────────────────

    /**
     * 对用户消息进行意图路由分析，返回结构化 JSON 路由指令。
     *
     * <p>调用链：
     * <ol>
     *   <li>将 {@code GATEKEEPER_SYSTEM_PROMPT} 作为系统提示词注入，锁定模型角色。</li>
     *   <li>将 {@code userMessage} 作为 user 消息发送给 DeepSeek Flash。</li>
     *   <li>接收原始响应，清除可能残留的 Markdown 代码块标记。</li>
     *   <li>返回纯净 JSON 字符串，供上游调度器解析 {@code intent} 字段。</li>
     * </ol>
     *
     * <p>异常处理策略：任何异常（网络超时、API 限流、解析失败等）均被捕获，
     * 返回 {@link #FALLBACK_RESPONSE}（DIRECT_CHAT 兜底），保证系统不因路由层
     * 异常而完全崩溃。
     *
     * @param userMessage 用户输入的原始自然语言消息
     * @return 包含 {@code intent} 和 {@code entities} 字段的合法 JSON 字符串；
     *         调用失败时返回 DIRECT_CHAT 兜底 JSON
     */
    public String routeRequest(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            log.warn("[Gatekeeper] Received blank user message, defaulting to DIRECT_CHAT.");
            return FALLBACK_RESPONSE;
        }

        log.info("[Gatekeeper] Routing request: \"{}\"", abbreviate(userMessage, 60));

        try {
            String rawResponse = chatClient.prompt()
                    .system(GATEKEEPER_SYSTEM_PROMPT)
                    .user(userMessage)
                    .call()
                    .content();

            String cleaned = stripMarkdownFences(rawResponse);

            log.info("[Gatekeeper] Route result: {}", cleaned);
            return cleaned;

        } catch (Exception e) {
            // 路由层异常不允许上抛——降级返回 DIRECT_CHAT，避免雪崩
            log.error("[Gatekeeper] Model call failed, falling back to DIRECT_CHAT. Cause: {}",
                    e.getMessage());
            return FALLBACK_RESPONSE;
        }
    }

    // ── 私有辅助方法 ───────────────────────────────────────────────────────────

    /**
     * 剥离大模型响应中可能出现的 Markdown 代码块标记。
     *
     * <p>处理变体：{@code ```json\n...\n```} 和 {@code ```\n...\n```}。
     *
     * @param raw 大模型返回的原始字符串
     * @return 去除 Markdown 包装后的纯净字符串；为空时返回兜底 JSON
     */
    private static String stripMarkdownFences(String raw) {
        if (raw == null || raw.isBlank()) {
            return FALLBACK_RESPONSE;
        }
        String s = raw.strip();
        if (s.startsWith("```")) {
            int newline = s.indexOf('\n');
            s = (newline != -1) ? s.substring(newline + 1).strip() : s.substring(3).strip();
        }
        if (s.endsWith("```")) {
            s = s.substring(0, s.length() - 3).strip();
        }
        return s.isBlank() ? FALLBACK_RESPONSE : s;
    }

    /** 截断字符串用于日志展示，避免超长消息刷爆日志。 */
    private static String abbreviate(String text, int maxLen) {
        return text.length() <= maxLen ? text : text.substring(0, maxLen) + "...";
    }
}
