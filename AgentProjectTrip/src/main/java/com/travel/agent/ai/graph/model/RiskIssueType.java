package com.travel.agent.ai.graph.model;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonValue;

/**
 * 风险审查问题类型。
 *
 * <p>系统架构位置：TripRiskReasoningNode -> <b>RiskIssueType</b> -> PlanRevisionNode / FinalizeAnswerNode</p>
 *
 * <p>职责：
 * <ul>
 *   <li>限定第四阶段风险推理节点可以输出的问题类型。</li>
 *   <li>让自动修正节点根据机器可读类型生成稳定的 revision prompt。</li>
 *   <li>避免只依赖自然语言 message 判断是否需要修正。</li>
 * </ul>
 * </p>
 */
public enum RiskIssueType {

    /** 天气、季节或户外安排之间存在冲突。 */
    WEATHER_CONFLICT,

    /** 用户要求避开人多，但草案安排大量高人流景点或高峰时段。 */
    CROWD_CONFLICT,

    /** 预算估算和用户预算约束冲突。 */
    BUDGET_CONFLICT,

    /** 行程天数和用户给出的 durationDays 不匹配。 */
    DURATION_MISMATCH,

    /** 草案没有覆盖用户指定的全部目的地。 */
    DESTINATION_MISMATCH,

    /** 用户说不含国际机票，但草案预算中把国际机票算入了总额。 */
    FLIGHT_BUDGET_CONFLICT,

    /** 跨城交通、换乘或移动节奏存在明显风险。 */
    TRANSPORT_RISK,

    /** 单日安排过密，强度不合理。 */
    OVERLOADED_DAY,

    /** RAG 防坑信息没有被草案吸收，或知识库上下文不足。 */
    RAG_WARNING,

    /** 分支工具不可用，草案不应伪造对应实时数据。 */
    TOOL_UNAVAILABLE,

    /** 景点、餐厅、交通或服务开放时间与行程安排存在冲突。 */
    OPERATING_HOURS,

    /** 行程依赖预约、购票或固定入场时段，缺少预订会影响可执行性。 */
    BOOKING_REQUIRED,

    /** 模型返回了当前系统尚未显式建模的风险类型。 */
    UNKNOWN;

    /**
     * 将模型返回的风险类型文本转换为系统枚举。
     *
     * <p>风险审查节点的输入来自大模型，模型偶尔会返回未登记的新类型。
     * 这里统一做大小写兼容和 UNKNOWN 兜底，避免 Jackson 反序列化失败后丢弃整次模型审查结果。</p>
     *
     * @param value 模型返回的风险类型文本
     * @return 匹配到的风险类型；未知时返回 UNKNOWN
     */
    @JsonCreator
    public static RiskIssueType fromJson(String value) {
        if (value == null || value.isBlank()) {
            return UNKNOWN;
        }
        String normalized = value.trim();
        for (RiskIssueType type : values()) {
            if (type.name().equalsIgnoreCase(normalized)) {
                return type;
            }
        }
        return UNKNOWN;
    }

    /**
     * 将枚举按稳定名称输出给 JSON。
     *
     * <p>前端、日志和后续节点都依赖枚举名作为机器可读类型，因此序列化时不做本地化翻译。</p>
     */
    @JsonValue
    public String toJson() {
        return name();
    }
}
