package com.travel.agent.core.service.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.travel.agent.core.dto.WeatherDTO;
import com.travel.agent.core.service.WeatherService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * 天气业务服务实现类（Service 层 - 数据获取核心）
 *
 * <p>系统架构位置：Tools 层 → <b>Service 层</b> → 外部 OpenWeatherMap API</p>
 *
 * <p>职责：
 * <ul>
 *   <li>通过 OpenWeatherMap 的 Current Weather Data 接口获取实时天气数据。</li>
 *   <li>解析 JSON 响应，提取温度、湿度、天气描述等关键字段，封装为 {@link WeatherDTO}。</li>
 *   <li>对所有外部调用加装完整的异常防护，确保 API 故障（如城市名拼写错误、网络超时）
 *       不会向上传播导致整个 Agent 推理链崩溃，始终返回有意义的兜底数据。</li>
 * </ul>
 * </p>
 *
 * <p><b>API 文档参考：</b>
 * <a href="https://openweathermap.org/current">OpenWeatherMap Current Weather</a></p>
 */
@Service
public class WeatherServiceImpl implements WeatherService {

    private static final Logger log = LoggerFactory.getLogger(WeatherServiceImpl.class);

    /** OpenWeatherMap Current Weather 接口地址 */
    private static final String OWM_BASE_HOST = "api.openweathermap.org";

    /** 查询失败时返回的兜底城市名占位符 */
    private static final String FALLBACK_CITY = "未知城市";

    /** 查询失败时返回的兜底天气描述 */
    private static final String FALLBACK_DESCRIPTION = "获取天气失败";

    private final RestClient restClient;

    /** 从环境变量 OPENWEATHER_API_KEY 读取的 API 密钥，由 application.properties 桥接注入 */
    private final String apiKey;

    /**
     * 构造器注入 RestClient 和 OpenWeatherMap 密钥。
     *
     * <p>使用 {@link RestClient.Builder} 而非直接注入 {@link RestClient}，
     * 是为了允许 Spring Boot 自动配置（如超时、拦截器）在构建阶段生效。</p>
     *
     * @param builder Spring Boot 自动配置提供的 {@link RestClient.Builder}
     * @param apiKey  从配置项 {@code openweathermap.api-key} 注入的 API 密钥
     */
    public WeatherServiceImpl(RestClient.Builder builder,
                              @Value("${openweathermap.api-key}") String apiKey) {
        this.restClient = builder.build();
        this.apiKey = apiKey;
    }

    /**
     * {@inheritDoc}
     *
     * <p>请求 URL 示例：
     * {@code https://api.openweathermap.org/data/2.5/weather?q=Paris&appid=xxx&units=metric&lang=zh_cn}</p>
     *
     * <p>响应 JSON 字段映射：
     * <ul>
     *   <li>{@code name}               → cityName（城市名称，OpenWeatherMap 官方名）</li>
     *   <li>{@code main.temp}          → temperature（当前气温，°C）</li>
     *   <li>{@code main.humidity}      → humidity（湿度，%）</li>
     *   <li>{@code weather[0].description} → description（中文天气描述）</li>
     * </ul>
     * </p>
     */
    @Override
    public WeatherDTO getWeatherByCity(String city) {
        log.info("[OpenWeatherMap] 查询城市天气：{}", city);

        try {
            // 通过 UriBuilder 安全构建带查询参数的请求 URL，避免手动拼接引发的注入或编码问题
            JsonNode root = restClient.get()
                    .uri(uriBuilder -> uriBuilder
                            .scheme("https")
                            .host(OWM_BASE_HOST)
                            .path("/data/2.5/weather")
                            .queryParam("q", city)
                            .queryParam("appid", apiKey)
                            // units=metric：温度返回摄氏度（°C），无需客户端再做单位换算
                            .queryParam("units", "metric")
                            // lang=zh_cn：让 weather[0].description 字段直接返回中文描述
                            .queryParam("lang", "zh_cn")
                            .build())
                    .retrieve()
                    // 直接反序列化为 JsonNode 树，便于按路径灵活提取字段
                    .body(JsonNode.class);

            // OpenWeatherMap 正常情况下不会返回空体，但做防御性判空
            if (root == null) {
                log.warn("[OpenWeatherMap] 响应体为空，城市：{}", city);
                return buildFallback(city);
            }

            // 提取城市名（OpenWeatherMap 标准化后的官方名称，如 "Paris" → "Paris"）
            String cityName = root.path("name").asText(city);

            // 提取温度：main.temp，单位 °C（已通过 units=metric 指定）
            double temperature = root.path("main").path("temp").asDouble(0.0);

            // 提取湿度：main.humidity，单位 %
            int humidity = root.path("main").path("humidity").asInt(0);

            // 提取天气描述：weather 是数组，取第一个元素的 description 字段
            // 使用 path() 链式调用，路径不存在时返回 MissingNode，asText() 不会抛空指针
            String description = FALLBACK_DESCRIPTION;
            JsonNode weatherArray = root.path("weather");
            if (weatherArray.isArray() && !weatherArray.isEmpty()) {
                description = weatherArray.get(0).path("description").asText(FALLBACK_DESCRIPTION);
            }

            log.info("[OpenWeatherMap] 查询成功 | 城市：{} | 温度：{}°C | 湿度：{}% | 状况：{}",
                    cityName, temperature, humidity, description);

            return new WeatherDTO(cityName, temperature, description, humidity);

        } catch (RestClientException e) {
            // 网络故障、HTTP 4xx（如城市不存在返回 404）、5xx 等 RestClient 层面的错误
            log.error("[OpenWeatherMap] HTTP 请求失败，城市：{}，错误：{}", city, e.getMessage(), e);
            return buildFallback(city);
        } catch (Exception e) {
            // JSON 结构异常、字段类型不匹配等解析层面的兜底捕获
            // 任何未预期的异常均在此拦截，保证 Agent 推理链的鲁棒性
            log.error("[OpenWeatherMap] 解析响应时发生意外错误，城市：{}，错误：{}", city, e.getMessage(), e);
            return buildFallback(city);
        }
    }

    /**
     * 构造查询失败时的兜底 {@link WeatherDTO}。
     *
     * <p>保留原始入参 {@code city} 作为城市名，让调用方（如 AI 工具）仍能感知
     * 是哪个城市查询失败，便于在回复中给出有意义的提示。</p>
     *
     * @param city 原始查询城市名
     * @return 温度为 0、湿度为 0、描述为 "获取天气失败" 的兜底对象
     */
    private WeatherDTO buildFallback(String city) {
        // 若 city 为空字符串，使用占位符，避免返回对用户无意义的空白城市名
        String displayCity = (city == null || city.isBlank()) ? FALLBACK_CITY : city;
        return new WeatherDTO(displayCity, 0.0, FALLBACK_DESCRIPTION, 0);
    }
}
