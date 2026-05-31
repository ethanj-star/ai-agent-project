package com.travel.agent.ai.agents;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.travel.agent.ai.dto.GatekeeperResponse;
import com.travel.agent.ai.graph.LangGraphPlannerFacade;
import com.travel.agent.ai.graph.model.GraphInputRequest;
import com.travel.agent.ai.graph.model.GraphResult;
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
 *   plannerFacade    → DeepSeek Pro    · PLAN_OR_RAG 直线规划工作流
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
    /**
     * PLAN_OR_RAG 的第一阶段直线规划黑箱。
     *
     * <p>MastermindAgent 只负责路由，不再直接拼接复杂规划 Prompt；
     * 复杂任务的状态流转、RAG 注入、草案生成和校验均交给 Facade 内部完成。</p>
     */
    private final LangGraphPlannerFacade langGraphPlannerFacade;
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
            LangGraphPlannerFacade langGraphPlannerFacade,
            @Qualifier("branchChatModel") ChatModel branchChatModel) {

        // builder 由 @Primary branchChatModel 驱动
        this.chatClient     = builder.build();
        this.flightTools    = flightTools;
        this.weatherTools   = weatherTools;
        this.placesTools    = placesTools;
        this.knowledgeTools = knowledgeTools;

        this.gatekeeperAgent  = gatekeeperAgent;
        this.objectMapper     = objectMapper;
        // PLAN_OR_RAG 进入 graph 黑箱，保持 MastermindAgent 的职责聚焦在路由和降级
        this.langGraphPlannerFacade = langGraphPlannerFacade;
        // 为 branch 构建独立 ChatClient，与 builder 驱动的 chatClient 相互隔离
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
        return handleUserWorkflow(userMessage, null);
    }

    /**
     * 完整的多智能体工作流入口，支持通过 sessionId 续跑上一轮需要澄清的规划任务。
     *
     * @param userMessage 用户自然语言输入
     * @param sessionId   当前会话 ID，可为空
     * @return 最终自然语言答复
     */
    public String handleUserWorkflow(String userMessage, String sessionId) {
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
        intent = normalizeIntent(intent, userMessage);
        route.setIntent(intent);
        GatekeeperResponse.Entities entities = route.getEntities();

        // ── Step 3：按意图分发 ────────────────────────────────────────────────
        return switch (intent) {
            case "DIRECT_CHAT" -> buildDirectChatReply();
            case "TOOL_WEATHER", "TOOL_FLIGHT" -> handleToolBranch(intent, entities, userMessage);
            case "PLAN_OR_RAG" -> handlePlanOrRag(route, userMessage, sessionId);
            default -> {
                log.warn("[Mastermind] Unknown intent '{}', defaulting to PLAN_OR_RAG.", intent);
                yield handlePlanOrRag(route, userMessage, sessionId);
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

        return "收到您关于【" + locationStr + "】的" + toolType + "查询请求。第一阶段已优先搭好复杂行程规划直线流程，" +
               "天气和航班工具分支会在下一阶段接入。您也可以把需求描述成完整行程规划，我会进入规划流程。";
    }

    /**
     * PLAN_OR_RAG 分支：进入第一阶段直线规划黑箱。
     *
     * <p>处理流程：
     * <ol>
     *   <li>将用户原始输入和完整 Gatekeeper 路由结果打包为 {@link GraphInputRequest}。</li>
     *   <li>调用 {@link LangGraphPlannerFacade} 执行 Init → RAG → Planner → Validator → Finalizer。</li>
     *   <li>成功时返回 Graph 生成的 Markdown 答案。</li>
     *   <li>失败时优先返回 GraphResult 中的降级答案，避免 Web 层收到空响应。</li>
     * </ol>
     * </p>
     *
     * @param route       Gatekeeper 完整路由结果
     * @param userMessage 用户原始自然语言输入
     * @return 最终用户可读回复
     */
    private String handlePlanOrRag(GatekeeperResponse route, String userMessage, String sessionId) {
        log.info("[Mastermind] Routing to PLAN_OR_RAG linear graph workflow.");

        // GraphInputRequest 是 Spring AI 外围层进入 Graph 黑箱的稳定边界协议
        GraphInputRequest request = new GraphInputRequest(userMessage, route, sessionId);
        GraphResult result = langGraphPlannerFacade.plan(request);

        // Graph 成功时直接透传最终 Markdown；Mastermind 不再二次改写复杂规划内容
        if (result.isSuccess() && hasText(result.getAnswer())) {
            return result.getAnswer();
        }

        log.error("[Mastermind] PLAN_OR_RAG graph failed: {}", result.getErrorMessage());
        // Graph 失败但提供了可读降级答案时，仍然优先返回该答案
        if (hasText(result.getAnswer())) {
            return result.getAnswer();
        }
        return "抱歉，规划流程暂时遇到了一点问题。请稍后重试，或者先补充更多具体旅行需求。";
    }

    /**
     * 对 Gatekeeper 的意图结果做轻量确定性纠偏。
     *
     * <p>第一阶段测试中，用户说“下个月去欧洲玩，帮我安排”这类开放式规划请求时，
     * 偶发会被模型误判为 {@code TOOL_FLIGHT}。这里用代码规则兜底：只要用户明显在请求
     * 安排、规划、行程、攻略，且没有明确提到机票 / 航班 / 天气，就强制进入
     * {@code PLAN_OR_RAG}。</p>
     *
     * @param intent      Gatekeeper 原始意图
     * @param userMessage 用户原始输入
     * @return 纠偏后的意图
     */
    private String normalizeIntent(String intent, String userMessage) {
        if (("TOOL_FLIGHT".equals(intent) || "TOOL_WEATHER".equals(intent))
                && looksLikePlanningRequest(userMessage)
                && !hasExplicitToolKeyword(userMessage)) {
            log.info("[Mastermind] Override Gatekeeper intent {} -> PLAN_OR_RAG for planning-like request.", intent);
            return "PLAN_OR_RAG";
        }
        return intent;
    }

    /**
     * 判断用户是否在表达开放式旅行规划需求。
     */
    private static boolean looksLikePlanningRequest(String message) {
        if (!hasText(message)) {
            return false;
        }
        return message.contains("规划")
                || message.contains("安排")
                || message.contains("行程")
                || message.contains("攻略")
                || message.contains("怎么玩")
                || message.contains("去欧洲玩")
                || message.contains("旅游");
    }

    /**
     * 判断用户是否明确请求某个工具类能力。
     */
    private static boolean hasExplicitToolKeyword(String message) {
        if (!hasText(message)) {
            return false;
        }
        return message.contains("机票")
                || message.contains("航班")
                || message.contains("飞")
                || message.contains("机场")
                || message.contains("天气")
                || message.contains("下雨")
                || message.contains("温度");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
