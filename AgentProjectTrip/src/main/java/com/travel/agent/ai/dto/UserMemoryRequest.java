package com.travel.agent.ai.dto;

import com.travel.agent.ai.graph.model.MemoryScope;
import com.travel.agent.ai.graph.model.MemorySource;
import com.travel.agent.ai.graph.model.MemoryType;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 用户记忆写入请求 DTO。
 *
 * <p>系统架构位置：前端 / 调试台 -> <b>UserMemoryRequest</b> -> MemoryController -> UserMemoryService</p>
 *
 * <p>职责：
 * <ul>
 *   <li>承载用户手动新增长期或短期记忆的请求参数。</li>
 *   <li>支持传入 scope/type/source，保证记忆来源和语义可审计。</li>
 *   <li>预留 metadata，用于后续关联 requirementId、planId 或版本号。</li>
 * </ul>
 * </p>
 */
public class UserMemoryRequest {

    /** 可选用户 ID；开发期为空时由 sessionId 充当 userId。 */
    private String userId;

    /** 当前会话 ID。 */
    private String sessionId;

    /** 记忆作用域，默认长期记忆，适合前端手动新增偏好。 */
    private MemoryScope scope = MemoryScope.LONG_TERM;

    /** 记忆类型，默认偏好。 */
    private MemoryType type = MemoryType.PREFERENCE;

    /** 机器可读键，例如 accommodationPreference。 */
    private String key;

    /** 用户可读记忆值，例如“不住青旅”。 */
    private String value;

    /** 记忆来源，默认用户明确表达。 */
    private MemorySource source = MemorySource.USER_EXPLICIT;

    /** 可信度，默认 1.0。 */
    private double confidence = 1.0;

    /** 扩展元数据。 */
    private Map<String, Object> metadata = new LinkedHashMap<>();

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
        this.scope = scope;
    }

    public MemoryType getType() {
        return type;
    }

    public void setType(MemoryType type) {
        this.type = type;
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
        this.source = source;
    }

    public double getConfidence() {
        return confidence;
    }

    public void setConfidence(double confidence) {
        this.confidence = confidence;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata == null ? new LinkedHashMap<>() : metadata;
    }
}
