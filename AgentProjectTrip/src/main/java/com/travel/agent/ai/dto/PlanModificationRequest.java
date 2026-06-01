package com.travel.agent.ai.dto;

/**
 * 已生成行程的自然语言修改请求 DTO。
 *
 * <p>系统架构位置：前端 -> <b>PlanModificationRequest</b> -> 后续 PlanModificationAgent</p>
 *
 * <p>职责：
 * <ul>
 *   <li>为第五阶段后半段“第一版结果后的自然语言修改”预留稳定入参。</li>
 *   <li>第一版代码先完成需求确认和生成门控，后续再接入真正的局部重规划。</li>
 * </ul>
 * </p>
 */
public class PlanModificationRequest {

    /** 当前会话 ID，可为空。 */
    private String sessionId;

    /** 用户对已有行程提出的自然语言修改意见。 */
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
