package com.travel.agent.web;

import com.travel.agent.ai.agents.MastermindAgent;
import com.travel.agent.core.dto.FlightDTO;
import com.travel.agent.core.service.FlightService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 旅行服务 RESTful 控制器（Web 层 - HTTP 接口入口）
 *
 * <p>系统架构位置：<b>Web 层</b> → Agent 层 / Service 层</p>
 *
 * <p>职责：将系统的核心能力（航班查询、AI 对话）以 HTTP GET 接口的形式对外暴露，
 * 所有接口挂载在 {@code /api/v1/travel} 路径下，遵循 RESTful 版本化规范。</p>
 *
 * <p>接口清单：
 * <ul>
 *   <li>{@code GET /api/v1/travel/health}   — 服务健康探针</li>
 *   <li>{@code GET /api/v1/travel/flights}  — 原始航班数据查询（绕过 AI，直接调用 Service 层）</li>
 *   <li>{@code GET /api/v1/travel/chat}     — AI 对话入口（经过 Agent 推理链，含 Function Calling）</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/v1/travel")
public class TravelController {

    private final FlightService flightService;
    private final MastermindAgent mastermindAgent;

    /**
     * 构造器注入依赖，遵循 Spring 推荐的不可变注入风格。
     *
     * @param flightService   航班业务服务，用于直接提供结构化航班数据
     * @param mastermindAgent AI 总指挥 Agent，用于处理自然语言对话请求
     */
    public TravelController(FlightService flightService, MastermindAgent mastermindAgent) {
        this.flightService = flightService;
        this.mastermindAgent = mastermindAgent;
    }

    /**
     * 服务健康探针接口。
     *
     * <p>供负载均衡器、容器编排平台（如 Kubernetes）或运维监控系统调用，
     * 用于确认应用进程已正常启动并可接受流量。</p>
     *
     * @return 固定字符串 {@code "ok"}，表示服务运行正常
     */
    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    /**
     * 原始航班数据查询接口（直连 Service 层，绕过 AI 推理）。
     *
     * <p>适用于前端需要获取结构化 JSON 数据并自行渲染的场景，
     * 或用于调试验证 SerpApi 集成与 Redis 缓存是否正常工作。
     * 查询结果受 {@link org.springframework.cache.annotation.Cacheable} 保护，
     * 相同路线在 TTL 内重复请求将直接命中 Redis 缓存。</p>
     *
     * @param origin      出发地 IATA 三字码，默认值 {@code "DUB"}（都柏林）
     * @param destination 目的地 IATA 三字码，默认值 {@code "CDG"}（巴黎戴高乐）
     * @param date        出发日期，格式 YYYY-MM-DD，默认值 {@code "2024-12-01"}
     * @return 匹配的航班 DTO 列表，直接序列化为 JSON 响应体
     */
    @GetMapping("/flights")
    public List<FlightDTO> searchFlights(
            @RequestParam(defaultValue = "DUB") String origin,
            @RequestParam(defaultValue = "CDG") String destination,
            @RequestParam(defaultValue = "2024-12-01") String date) {
        return flightService.searchFlights(origin, destination, date);
    }

    /**
     * AI 自然语言对话接口（经过 Agent 推理链，含 Function Calling）。
     *
     * <p>用户以自然语言描述需求，{@link MastermindAgent} 将驱动大模型进行多步推理：
     * 自主决策是否调用 {@code searchFlights} 工具获取真实数据，
     * 最终以自然语言形式返回综合性的旅行建议。</p>
     *
     * <p>示例请求：
     * {@code GET /api/v1/travel/chat?message=帮我查一下明天从都柏林飞巴黎的最便宜机票}</p>
     *
     * @param message 用户输入的自然语言消息
     * @return 大模型经过推理（含可能的工具调用）后生成的自然语言答复字符串
     */
    @GetMapping("/chat")
    public String chat(@RequestParam String message) {
        return mastermindAgent.chat(message);
    }
}
