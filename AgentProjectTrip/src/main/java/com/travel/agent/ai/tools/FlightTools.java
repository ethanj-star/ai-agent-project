package com.travel.agent.ai.tools;

import com.travel.agent.core.dto.FlightDTO;
import com.travel.agent.core.service.FlightService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * 航班查询工具集（AI 层 - 工具桥接器）
 *
 * <p>系统架构位置：Agent 层 → <b>Tools 层</b> → Service 层</p>
 *
 * <p>职责：作为 AI 推理世界与真实业务服务之间的桥梁。
 * 本类中被 {@link Tool} 标注的方法会在启动时被 Spring AI 框架自动解析，
 * 其方法签名和 {@code description} 将被序列化为函数定义（Function Schema）
 * 发送给大模型，大模型据此理解"何时调用、传入什么参数"。</p>
 *
 * <p>当大模型在推理链中判断需要查询航班时，框架将自动拦截大模型的
 * Function Calling 响应，反射调用对应方法，并将结果回填到对话上下文中，
 * 完成"感知 → 决策 → 行动"的 Agent 闭环。</p>
 */
@Component
public class FlightTools {

    private final FlightService flightService;

    /**
     * 构造器注入航班业务服务。
     *
     * @param flightService 航班核心业务服务，负责实际的 SerpApi 调用与缓存管理
     */
    public FlightTools(FlightService flightService) {
        this.flightService = flightService;
    }

    /**
     * 查询指定路线的可用航班列表。
     *
     * <p>{@link Tool#description()} 是大模型意图识别的核心依据：该字符串会被直接嵌入
     * 发送给大模型的 Function Schema 中。描述越精确，大模型越能正确判断调用时机
     * 并提取出符合格式要求的参数（如 IATA 三字码、ISO 日期格式）。</p>
     *
     * @param origin      出发地 IATA 三字码，例如 "DUB"（都柏林）、"CDG"（巴黎戴高乐）
     * @param destination 目的地 IATA 三字码，例如 "FCO"（罗马菲乌米奇诺）
     * @param date        出发日期，严格遵循 ISO 8601 格式：YYYY-MM-DD，例如 "2026-05-20"
     * @return 匹配的航班列表，每条记录包含航司名称和单程价格（欧元）；若查询失败则返回空列表
     */
    @Tool(description = "根据出发地和目的地的三字机场代码（IATA）及日期查询单程航班价格。注意：必须使用具体的物理机场代码（例如：飞往巴黎必须使用 'CDG' 或 'ORY'，绝对不要使用泛城市代码 'PAR'；伦敦使用 'LHR' 等）。日期格式必须为 YYYY-MM-DD。")
    public List<FlightDTO> searchFlights(String origin, String destination, String date) {
        return flightService.searchFlights(origin, destination, date);
    }
}
