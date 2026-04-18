package com.travel.agent.ai.tools;

import com.travel.agent.core.dto.WeatherDTO;
import com.travel.agent.core.service.WeatherService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

/**
 * 天气查询工具集（AI 层 - 工具桥接器）
 *
 * <p>系统架构位置：Agent 层 → <b>Tools 层</b> → Service 层</p>
 *
 * <p>职责：作为 AI 推理世界与天气业务服务之间的桥梁。
 * 本类中被 {@link Tool} 标注的方法会在启动时被 Spring AI 框架自动解析，
 * 其方法签名和 {@code description} 将被序列化为 Function Schema 发送给大模型，
 * 大模型据此理解"何时调用、传入什么参数"。</p>
 *
 * <p>当大模型在推理链中判断需要查询目的地天气时，框架将自动拦截大模型的
 * Function Calling 响应，反射调用 {@link #getWeather(String)}，
 * 并将结果回填到对话上下文中，完成"感知 → 决策 → 行动"的 Agent 闭环。</p>
 */
@Component
public class WeatherTools {

    private final WeatherService weatherService;

    /**
     * 构造器注入天气业务服务。
     *
     * @param weatherService 天气核心业务服务，负责实际的 OpenWeatherMap API 调用
     */
    public WeatherTools(WeatherService weatherService) {
        this.weatherService = weatherService;
    }

    /**
     * 查询指定城市的当前实时天气信息。
     *
     * <p>{@link Tool#description()} 是大模型意图识别的核心依据：该字符串会被直接嵌入
     * 发送给大模型的 Function Schema 中。此处使用中文城市举例，引导大模型在用户提及
     * 欧洲目的地时主动触发天气查询，丰富行程规划的上下文信息。</p>
     *
     * @param city 城市名称，建议使用英文（如 "Paris"、"London"、"Dublin"），
     *             也支持中文拼音；大模型应将用户提及的目的地自动转换为对应英文城市名
     * @return 包含城市名、气温（°C）、天气描述、湿度的 {@link WeatherDTO}；
     *         查询失败时返回描述为 "获取天气失败" 的兜底对象，不会抛出异常
     */
    @Tool(description = "根据城市名称查询实时天气。注意：传入的参数必须是该城市的英文名称（例如：'Paris' 而非 '巴黎', 'London' 而非 '伦敦'）。")
    public WeatherDTO getWeather(String city) {
        return weatherService.getWeatherByCity(city);
    }
}
