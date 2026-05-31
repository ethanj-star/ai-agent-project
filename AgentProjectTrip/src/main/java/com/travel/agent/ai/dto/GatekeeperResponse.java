package com.travel.agent.ai.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

/**
 * Gatekeeper 路由 JSON 的 Java 映射对象。
 *
 * <p>与 {@link com.travel.agent.ai.agents.GatekeeperAgent} 输出的 JSON 结构一一对应：
 * <pre>{@code
 * {
 *   "intent": "PLAN_OR_RAG",
 *   "entities": {
 *     "locations": ["瑞士", "意大利"],
 *     "time": "国庆节",
 *     "keywords": ["10天", "行程规划"]
 *   }
 * }
 * }</pre>
 *
 * <p>{@code @JsonIgnoreProperties(ignoreUnknown = true)} 保证大模型偶发多余字段时不会抛出异常。
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GatekeeperResponse {

    /**
     * 意图枚举值，取自 Gatekeeper System Prompt 规定的四个值：
     * {@code DIRECT_CHAT} / {@code TOOL_WEATHER} / {@code TOOL_FLIGHT} / {@code PLAN_OR_RAG}
     */
    @JsonProperty("intent")
    private String intent;

    @JsonProperty("entities")
    private Entities entities;

    /** DIRECT_CHAT 场景下，Gatekeeper 低成本模型直接生成的回复文本。 */
    @JsonProperty("direct_reply")
    private String directReply;

    public String getIntent() {
        return intent;
    }

    public void setIntent(String intent) {
        this.intent = intent;
    }

    public Entities getEntities() {
        return entities;
    }

    public void setEntities(Entities entities) {
        this.entities = entities;
    }

    public String getDirectReply() {
        return directReply;
    }

    public void setDirectReply(String directReply) {
        this.directReply = directReply;
    }

    // ── 内部类：实体信息 ──────────────────────────────────────────────────────

    /**
     * Gatekeeper 从用户输入中提取的结构化实体。
     */
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class Entities {

        /** 识别到的国家 / 城市名称列表；无则为空 List */
        @JsonProperty("locations")
        private List<String> locations;

        /** 用户提及的时间表达；无则为 null */
        @JsonProperty("time")
        private String time;

        /** 其他关键词（如"防坑"、"10天"等）；无则为空 List */
        @JsonProperty("keywords")
        private List<String> keywords;

        public List<String> getLocations() {
            return locations;
        }

        public void setLocations(List<String> locations) {
            this.locations = locations;
        }

        public String getTime() {
            return time;
        }

        public void setTime(String time) {
            this.time = time;
        }

        public List<String> getKeywords() {
            return keywords;
        }

        public void setKeywords(List<String> keywords) {
            this.keywords = keywords;
        }
    }
}
