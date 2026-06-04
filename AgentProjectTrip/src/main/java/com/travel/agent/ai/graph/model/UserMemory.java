package com.travel.agent.ai.graph.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户记忆记录（Graph 层 - 用户偏好与事实载体）。
 *
 * <p>系统架构位置：MemoryController / UserMemoryService -> <b>UserMemory</b> -> UserMemoryStore / Planner</p>
 *
 * <p>职责：
 * <ul>
 *   <li>保存用户短期会话状态和长期旅行偏好。</li>
 *   <li>通过 scope/type/source/confidence 区分记忆的作用域、语义和可信度。</li>
 *   <li>为 Planner 注入用户长期偏好提供可审计、可删除的数据来源。</li>
 * </ul>
 * </p>
 *
 * <p>设计边界：本类只表达已经写入系统的记忆，不负责判断某句话是否应该被记住；
 * 写入策略由 UserMemoryService 控制，避免模型猜测直接污染长期记忆。</p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserMemory {

    /** 记忆 ID，保存、禁用和审计都围绕它。 */
    private String memoryId;

    /** 用户 ID；开发期可由 sessionId 临时充当。 */
    private String userId;

    /** 会话 ID；短期记忆通常需要绑定具体 session。 */
    private String sessionId;

    /** 记忆作用域：短期或长期。 */
    private MemoryScope scope = MemoryScope.SHORT_TERM;

    /** 记忆类型：偏好、约束、事实、历史或系统状态。 */
    private MemoryType type = MemoryType.PREFERENCE;

    /** 机器可读键，例如 accommodationPreference、avoidances、departureCity。 */
    private String key;

    /** 记忆内容，保持用户可读文本。 */
    private String value;

    /** 记忆来源，用于审计和后续删除策略。 */
    private MemorySource source = MemorySource.MANUAL;

    /** 可信度，用户明确表达通常为 1.0，模型推断默认不写入。 */
    private double confidence = 1.0;

    /** 是否仍然生效；删除接口默认只做软删除。 */
    private boolean active = true;

    /** 扩展元数据，例如来源 requirementId、planId、version。 */
    private Map<String, Object> metadata = new LinkedHashMap<>();

    /** 创建时间。 */
    private Instant createdAt = Instant.now();

    /** 最近更新时间。 */
    private Instant updatedAt = Instant.now();

    public String getMemoryId() {
        return memoryId;
    }

    public void setMemoryId(String memoryId) {
        this.memoryId = memoryId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public MemoryScope getScope() {
        return scope;
    }

    public void setScope(MemoryScope scope) {
        this.scope = scope == null ? MemoryScope.SHORT_TERM : scope;
    }

    public MemoryType getType() {
        return type;
    }

    public void setType(MemoryType type) {
        this.type = type == null ? MemoryType.PREFERENCE : type;
    }

    public String getKey() {
        return key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public MemorySource getSource() {
        return source;
    }

    public void setSource(MemorySource source) {
        this.source = source == null ? MemorySource.MANUAL : source;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = Math.max(0.0, Math.min(1.0, confidence));
    }

    public boolean isActive() {
        return active;
    }

    public void setActive(boolean active) {
        this.active = active;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? new LinkedHashMap<>() : metadata;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }
}
