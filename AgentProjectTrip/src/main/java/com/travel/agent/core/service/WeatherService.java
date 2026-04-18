package com.travel.agent.core.service;

import com.travel.agent.core.dto.WeatherDTO;

/**
 * 天气业务服务接口（Service 层 - 核心契约定义）
 *
 * <p>定义天气查询的业务能力边界。上层的 AI 工具桥接器（WeatherTools）或
 * Controller 层均应依赖此接口，而非具体实现类，遵循依赖倒置原则，
 * 便于后续切换数据源（如替换 OpenWeatherMap 为其他气象服务商）。</p>
 */
public interface WeatherService {

    /**
     * 根据城市名称查询当前实时天气。
     *
     * <p>城市名称支持英文（如 {@code "Paris"}）和中文拼音（如 {@code "Beijing"}），
     * 建议使用英文城市名以提高匹配准确率。</p>
     *
     * <p>此方法保证不抛出异常：若外部 API 调用失败，将返回一个包含错误提示的
     * 备用 {@link WeatherDTO}，而不是向调用方传播异常。</p>
     *
     * @param city 城市名称，例如 {@code "Paris"}、{@code "Tokyo"}、{@code "Dublin"}
     * @return 包含温度、湿度、天气描述的 {@link WeatherDTO}；
     *         查询失败时返回描述为 "获取天气失败" 的兜底对象
     */
    WeatherDTO getWeatherByCity(String city);
}
