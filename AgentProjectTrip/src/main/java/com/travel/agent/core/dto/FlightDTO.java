package com.travel.agent.core.dto;

import java.io.Serial;
import java.io.Serializable;

/**
 * 航班数据传输对象（DTO 层 - 不可变值对象）
 *
 * <p>采用 Java 16+ {@code record} 结构定义，所有字段在构造时一次性赋值，
 * 无 setter，天然线程安全，适合在多层之间传递只读数据。</p>
 *
 * <p>实现 {@link Serializable} 是 Redis 序列化的必要条件：Spring Cache 在将对象
 * 写入 Redis 时需要对其进行序列化，反序列化时依赖 {@code serialVersionUID} 进行版本校验。</p>
 *
 * @param id          航班唯一标识符，格式：{@code serpapi-{origin}-{destination}-{index}}
 * @param origin      出发地 IATA 三字码，例如 {@code "DUB"}（都柏林）
 * @param destination 目的地 IATA 三字码，例如 {@code "CDG"}（巴黎戴高乐）
 * @param airline     主运营航司的中文/英文名称，来源于 SerpApi 响应中的第一航段
 * @param priceEuros  单程票价，单位：欧元（EUR），来源于 SerpApi 响应的顶层 {@code price} 字段
 */
public record FlightDTO(
        String id,
        String origin,
        String destination,
        String airline,
        double priceEuros
) implements Serializable {

    /** 序列化版本号，保证 Redis 中已缓存的对象在类结构未变更时可被正确反序列化 */
    @Serial
    private static final long serialVersionUID = 1L;
}
