package com.travel.agent.ai.graph.store;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * JDBC JSON 字段读写辅助类。
 *
 * <p>系统架构位置：Jdbc*Store -> <b>JdbcJsonSupport</b> -> ObjectMapper / MySQL JSON 字段</p>
 *
 * <p>职责：
 * <ul>
 *   <li>统一把复杂 Graph 模型序列化为 JSON 字符串写入 MySQL。</li>
 *   <li>统一把 MySQL JSON 字段反序列化回 Java 对象。</li>
 *   <li>把 Jackson 异常包装成 IllegalStateException，让 Store 方法保持简洁。</li>
 * </ul>
 * </p>
 */
final class JdbcJsonSupport {

    /** Spring 注入的 ObjectMapper，包含 JavaTime 等模块配置。 */
    private final ObjectMapper objectMapper;

    /**
     * 构造 JSON 辅助对象。
     *
     * @param objectMapper JSON 序列化工具
     */
    JdbcJsonSupport(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    /**
     * 将对象序列化为 JSON 字符串。
     *
     * @param value 待序列化对象
     * @return JSON 字符串；空对象返回 null
     */
    String toJson(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            // Store 层无法恢复序列化失败，包装成运行时异常交给上层事务/调用方兜底。
            throw new IllegalStateException("Failed to serialize JSON column", e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为指定类型。
     *
     * @param json JSON 字符串
     * @param type 目标类型
     * @return 反序列化对象；空 JSON 返回 null
     */
    <T> T fromJson(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            // 数据库 JSON 损坏属于持久化异常，不能静默吞掉，否则会产生难排查的半空对象。
            throw new IllegalStateException("Failed to deserialize JSON column", e);
        }
    }

    /**
     * 将 JSON 字符串反序列化为泛型类型。
     *
     * @param json JSON 字符串
     * @param type 目标泛型类型
     * @return 反序列化对象；空 JSON 返回 null
     */
    <T> T fromJson(String json, TypeReference<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return objectMapper.readValue(json, type);
        } catch (Exception e) {
            // 泛型 JSON 主要用于 Map/List 字段，失败时同样显式暴露为持久化异常。
            throw new IllegalStateException("Failed to deserialize JSON column", e);
        }
    }
}
