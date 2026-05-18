package com.travel.agent.ai.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.ai.dto.GatekeeperResponse;
import com.travel.agent.ai.tools.FlightTools;
import com.travel.agent.ai.tools.KnowledgeTools;
import com.travel.agent.ai.tools.PlacesTools;
import com.travel.agent.ai.tools.WeatherTools;
import com.travel.agent.core.GatekeeperAgent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import java.time.LocalDate;
import java.util.Collections;
import java.util.List;

/**
 * 中央智能体编排大脑（MAS 核心调度器）
 *
 * <p>系统架构位置：Web 层 → <b>MastermindAgent</b> → [GatekeeperAgent → 路由] → 各专项 Agent / Tools
 *
 * <p>职责：
 * <ol>
 *   <li>保留原有 {@link #chat(String)} 接口，兼容现有的 {@code TravelController}（单模型全工具模式）。</li>
 *   <li>新增 {@link #handleUserWorkflow(String)} 作为"三擎 + 网关"的完整编排入口：
 *       先由 Gatekeeper 识别意图，再按意图分发到对应模型 / 工具链。</li>
 * </ol>
 *
 * <p>三擎分工：
 * <pre>
 *   gatekeeperAgent  → DeepSeek Flash  · 意图分类，零成本路由
 *   coreChatClient   → DeepSeek Pro    · PLAN_OR_RAG 高价值规划
 *   branchChatClient → Qwen 百炼       · TOOL_* 工具调用 / Function Calling
 * </pre>
 */
@Service
public class MastermindAgent {

    private static final Logger log = LoggerFactory.getLogger(MastermindAgent.class);

    // ── 原有字段（保持向后兼容）────────────────────────────────────────────────
    /** 由 @Primary branchChatModel 驱动，供原 chat() 方法使用 */
    private final ChatClient chatClient;
    private final FlightTools    flightTools;
    private final WeatherTools   weatherTools;
    private final PlacesTools    placesTools;
    private final KnowledgeTools knowledgeTools;

    // ── 新增字段（三擎编排）────────────────────────────────────────────────────
    private final GatekeeperAgent gatekeeperAgent;
    private final ObjectMapper    objectMapper;
    /** DeepSeek Pro：用于 PLAN_OR_RAG 高价值行程规划 */
    private final ChatClient coreChatClient;
    /** 阿里百炼 Qwen：用于 TOOL_* 工具调用分支 */
    private final ChatClient branchChatClient;

    public MastermindAgent(
            // 原有依赖
            ChatClient.Builder builder,
            FlightTools flightTools,
            WeatherTools weatherTools,
            PlacesTools placesTools,
            KnowledgeTools knowledgeTools,
            // 新增依赖
            GatekeeperAgent gatekeeperAgent,
            ObjectMapper objectMapper,
            @Qualifier("coreChatModel")   ChatModel coreChatModel,
            @Qualifier("branchChatModel") ChatModel branchChatModel) {

        // builder 由 @Primary branchChatModel 驱动
        this.chatClient     = builder.build();
        this.flightTools    = flightTools;
        this.weatherTools   = weatherTools;
        this.placesTools    = placesTools;
        this.knowledgeTools = knowledgeTools;

        this.gatekeeperAgent  = gatekeeperAgent;
        this.objectMapper     = objectMapper;
        // 为 core / branch 分别构建独立 ChatClient，与 builder 驱动的 chatClient 相互隔离
        this.coreChatClient   = ChatClient.create(coreChatModel);
        this.branchChatClient = ChatClient.create(branchChatModel);
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 新入口：三擎 + 网关 完整编排工作流
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * 完整的多智能体工作流入口：Gatekeeper 路由 → 按意图分发 → 返回答复。
     *
     * <p>路由规则（优先级从低到高）：
     * <ol>
     *   <li>{@code DIRECT_CHAT} — 写死友好引导语，零 Token 消耗。</li>
     *   <li>{@code TOOL_WEATHER / TOOL_FLIGHT} — 调用 branchChatClient（Qwen），待工具链就绪后接入 Function Calling。</li>
     *   <li>{@code PLAN_OR_RAG} — 调用 coreChatClient（DeepSeek Pro），注入地点与时间上下文。</li>
     * </ol>
     *
     * <p>降级策略：Gatekeeper JSON 解析失败时降级为 {@code DIRECT_CHAT}，保证链路不中断。
     *
     * @param userMessage 用户自然语言输入
     * @return 最终自然语言答复
     */
    public String handleUserWorkflow(String userMessage) {
        if (userMessage == null || userMessage.isBlank()) {
            return "您好！请问有什么旅行计划我可以帮您？";
        }

        // ── Step 1：Gatekeeper 意图识别 ────────────────────────────────────────
        String routeJson = gatekeeperAgent.routeRequest(userMessage);
        log.info("[Mastermind] Gatekeeper route: {}", routeJson);

        // ── Step 2：解析路由 JSON ──────────────────────────────────────────────
        GatekeeperResponse route;
        try {
            route = objectMapper.readValue(routeJson, GatekeeperResponse.class);
        } catch (Exception e) {
            // JSON 解析失败（大模型偶发输出异常）→ 降级为闲聊兜底
            log.warn("[Mastermind] Failed to parse Gatekeeper JSON, falling back to DIRECT_CHAT. raw={}", routeJson);
            return buildDirectChatReply();
        }

        String intent = route.getIntent() != null ? route.getIntent().toUpperCase() : "DIRECT_CHAT";
        GatekeeperResponse.Entities entities = route.getEntities();

        // ── Step 3：按意图分发 ────────────────────────────────────────────────
        return switch (intent) {
            case "DIRECT_CHAT" -> buildDirectChatReply();
            case "TOOL_WEATHER", "TOOL_FLIGHT" -> handleToolBranch(intent, entities, userMessage);
            case "PLAN_OR_RAG" -> handlePlanOrRag(entities, userMessage);
            default -> {
                log.warn("[Mastermind] Unknown intent '{}', defaulting to PLAN_OR_RAG.", intent);
                yield handlePlanOrRag(entities, userMessage);
            }
        };
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 原有接口（保持向后兼容，TravelController 仍使用此方法）
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * 单模型全工具模式（旧版入口，保留向后兼容）。
     * 所有五种工具均注册，大模型自主决策调用时机。
     *
     * @param userMessage 用户自然语言请求
     * @return 大模型经推理后的自然语言答复
     */
    public String chat(String userMessage) {
        String currentDate = LocalDate.now().toString();

        return chatClient.prompt()
                .system("你是一位经验丰富、严谨高效的欧洲旅行高级规划专家（Agent），你的任务是帮用户查机票、查天气、查酒店并规划完整行程。" +
                        "你拥有以下五种工具，必须根据用户意图自主决策调用时机：" +
                        "1. 'searchFlights'——查询航班机票；" +
                        "2. 'getWeather'——查询目的地实时天气；" +
                        "3. 'searchHotels'——查询酒店价格和评分（需要入住和退房日期）；" +
                        "4. 'searchAttractions'——查询城市热门景点；" +
                        "5. 'searchTravelGuide'——从私有知识库检索目的地攻略、防坑指南、交通建议等经验性内容。" +
                        "拿到数据后，请用清晰、专业的自然语言向用户总结，不要直接丢出冷冰冰的 JSON。" +
                        " 当前现实世界的系统日期是：" + currentDate + "。你在规划行程和推算时间窗口时，必须严格基于这个当前日期进行计算！")
                .user(userMessage)
                .tools(flightTools, weatherTools, placesTools, knowledgeTools)
                .call()
                .content();
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 私有分支处理方法
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * DIRECT_CHAT 分支：写死导向性回复，不消耗任何 Token。
     */
    private static String buildDirectChatReply() {
        return "您好！我是您的全能欧洲旅行管家 ✈️\n" +
               "我可以帮您：\n" +
               "• 规划法国、意大利、瑞士的高质量行程\n" +
               "• 查询机票与实时天气\n" +
               "• 推荐景点、酒店并提供防坑攻略\n\n" +
               "请问您想去哪里，或者有什么具体的旅行问题呢？";
    }

    /**
     * TOOL_WEATHER / TOOL_FLIGHT 分支：调用 branchChatClient（Qwen 百炼）。
     * 工具链正式接入后，此处将注册 WeatherTools / FlightTools 进行 Function Calling。
     */
    private String handleToolBranch(String intent,
                                    GatekeeperResponse.Entities entities,
                                    String userMessage) {
        String toolType = intent.equals("TOOL_WEATHER") ? "天气" : "机票/航班";
        List<String> locations = entities != null && entities.getLocations() != null
                ? entities.getLocations()
                : Collections.emptyList();
        String locationStr = locations.isEmpty() ? "您提及的目的地" : String.join("、", locations);

        log.info("[Mastermind] Routing to TOOL branch: type={}, locations={}", toolType, locationStr);

        try {
            return branchChatClient.prompt()
                    .system("你是一位欧洲旅行信息查询助手，用简洁友好的中文回复用户的" + toolType + "查询需求。" +
                            "目前工具链正在接入中，请先礼貌地告知用户已收到请求，并给出你所了解的" +
                            toolType + "相关建议或注意事项。")
                    .user(userMessage)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("[Mastermind] TOOL branch call failed for intent={}: {}", intent, e.getMessage());
            return "收到您关于【" + locationStr + "】的" + toolType + "查询请求。工具链正在升级中，" +
                   "请稍后重试，或改为询问我行程规划方面的问题 😊";
        }
    }

    /**
     * PLAN_OR_RAG 分支：调用 coreChatClient（DeepSeek Pro），注入地点与时间上下文。
     * 后续 RAG 知识库接入后，此处将加入 KnowledgeTools 检索结果作为上下文。
     */
    private String handlePlanOrRag(GatekeeperResponse.Entities entities,
                                   String userMessage) {
        String currentDate = LocalDate.now().toString();

        List<String> locations = entities != null && entities.getLocations() != null
                ? entities.getLocations()
                : Collections.emptyList();
        String time = entities != null && entities.getTime() != null
                ? entities.getTime()
                : "未指定";
        List<String> keywords = entities != null && entities.getKeywords() != null
                ? entities.getKeywords()
                : Collections.emptyList();

        String locationStr = locations.isEmpty() ? "欧洲（法意瑞方向）" : String.join("、", locations);
        String keywordStr  = keywords.isEmpty()  ? "无"               : String.join("、", keywords);

        log.info("[Mastermind] Routing to PLAN_OR_RAG (DeepSeek Pro): locations={}, time={}", locationStr, time);

        String systemPrompt =
                "你是一位深度专注于法国、意大利、瑞士三国旅游的高级规划专家，拥有丰富的实地经验和防坑知识。\n" +
                "用户的旅行意图已由前置网关识别，核心信息如下：\n" +
                "  - 目的地：" + locationStr + "\n" +
                "  - 出行时间：" + time + "\n" +
                "  - 关键诉求：" + keywordStr + "\n" +
                "  - 当前系统日期：" + currentDate + "\n\n" +
                "请充分展示你的统筹规划能力，给出专业、具体、接地气的旅行建议。" +
                "如涉及行程，请给出分天日程；如涉及防坑，请列出具体注意事项。" +
                "语气亲切，内容丰富，不要泛泛而谈。";

        try {
            return coreChatClient.prompt()
                    .system(systemPrompt)
                    .user(userMessage)
                    .call()
                    .content();
        } catch (Exception e) {
            log.error("[Mastermind] PLAN_OR_RAG (DeepSeek Pro) call failed: {}", e.getMessage());
            return "抱歉，规划引擎暂时遇到了一点问题。请稍后重试，或者您可以先告诉我更多具体的旅行需求 🙏";
        }
    }
}
