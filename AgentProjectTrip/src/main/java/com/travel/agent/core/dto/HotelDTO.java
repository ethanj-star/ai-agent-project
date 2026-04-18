package com.travel.agent.core.dto;

import java.io.Serial;
import java.io.Serializable;

/**
 * 酒店数据传输对象（DTO 层 - 不可变值对象）
 *
 * <p>采用 Java 16+ {@code record} 结构定义，所有字段在构造时一次性赋值，
 * 无 setter，天然线程安全，适合在多层之间传递只读数据。</p>
 *
 * <p>实现 {@link Serializable} 是 Redis 序列化的必要条件：Spring Cache 在将对象
 * 写入 Redis 时需要对其进行序列化，反序列化时依赖 {@code serialVersionUID} 进行版本校验。</p>
 *
 * @param name    酒店名称，来源于 SerpApi {@code properties[].name} 字段
 * @param price   每晚价格描述，来源于 {@code properties[].rate_per_night.lowest} 字段，
 *                包含货币符号的字符串，例如 "€120"；价格不可用时填 "价格未知"
 * @param rating  酒店综合评分（满分 5 分），来源于 {@code properties[].overall_rating}；
 *                无评分数据时填 0.0
 */
public record HotelDTO(
        String name,
        String price,
        double rating
) implements Serializable {

    /** 序列化版本号，保证 Redis 中已缓存的对象在类结构未变更时可被正确反序列化 */
    @Serial
    private static final long serialVersionUID = 1L;
}
