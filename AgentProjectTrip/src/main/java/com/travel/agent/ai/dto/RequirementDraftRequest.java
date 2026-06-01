package com.travel.agent.ai.dto;

/**
 * 需求表草稿抽取请求 DTO。
 *
 * <p>系统架构位置：Web 层 RequirementController -> <b>RequirementDraftRequest</b> -> RequirementExtractionAgent</p>
 *
 * <p>职责：
 * <ul>
 *   <li>承载用户在第五阶段入口输入的自然语言旅行需求。</li>
 *   <li>携带 sessionId，方便多轮补全时把新信息合并到同一张需求表。</li>
 * </ul>
 * </p>
 */
public class RequirementDraftRequest {

    /** 当前会话 ID，可为空；为空时由服务端按需求表 ID 兜底。 */
    private String sessionId;

    /** 用户自然语言需求，例如“国庆去法国和意大利玩10天”。 */
    private String message;

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }
}
