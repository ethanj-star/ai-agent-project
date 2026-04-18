package com.travel.agent.core.dto;

/**
 * 天气数据传输对象（DTO 层 - 不可变值对象）
 *
 * <p>采用 Java 16+ {@code record} 结构定义，所有字段在构造时一次性赋值，
 * 无 setter，天然线程安全，适合在 Service → Tools → Agent 各层间传递只读天气数据。</p>
 *
 * <p>注意：此 DTO 无需实现 {@link java.io.Serializable}，因为当前天气数据
 * 不经过 Redis 缓存（实时性要求高，缓存意义不大）。</p>
 *
 * @param cityName    城市名称，来源于 OpenWeatherMap 响应的 {@code name} 字段
 * @param temperature 当前气温，单位：摄氏度（°C），来源于 {@code main.temp}
 * @param description 天气状况描述，中文文本，来源于 {@code weather[0].description}，
 *                    例如 "晴转多云"、"小雨"；查询失败时填入 "获取天气失败"
 * @param humidity    空气湿度，单位：百分比（%），来源于 {@code main.humidity}
 */
public record WeatherDTO(
        String cityName,
        double temperature,
        String description,
        int humidity
) {}
