package com.travel.agent.core.service;

import org.springframework.stereotype.Component;

/**
 * 用户上下文解析器（Core 层 - 临时身份边界）。
 *
 * <p>系统架构位置：Web 层 / Store 层 -> <b>UserContextResolver</b> -> userId / sessionId</p>
 *
 * <p>职责：
 * <ul>
 *   <li>在正式登录系统接入前，把 sessionId 映射为开发期 userId。</li>
 *   <li>为额度、记忆、需求表和计划持久化提供统一用户键。</li>
 *   <li>避免不同模块各自发明 anonymous key，导致数据无法关联。</li>
 * </ul>
 * </p>
 */
@Component
public class UserContextResolver {

    /** 用户和会话都缺失时使用的开发期兜底身份。 */
    private static final String DEFAULT_USER_ID = "anonymous-default";

    /**
     * 解析当前请求对应的用户 ID。
     *
     * <p>优先级：显式 userId > sessionId > anonymous-default。后续接入登录后，
     * Controller 可以直接传入真实 userId，现有 Store 和记忆逻辑无需改动。</p>
     *
     * @param userId    请求中显式携带的用户 ID，可为空
     * @param sessionId 当前会话 ID，可为空
     * @return 归一化后的用户 ID
     */
    public String resolveUserId(String userId, String sessionId) {
        if (hasText(userId)) {
            return userId.trim();
        }
        if (hasText(sessionId)) {
            return sessionId.trim();
        }
        return DEFAULT_USER_ID;
    }

    /**
     * 解析当前请求对应的会话 ID。
     *
     * <p>会话 ID 用于短期记忆和 pending 状态。缺失时回退到 userId，
     * 仍缺失时使用开发期兜底值。</p>
     *
     * @param sessionId 当前会话 ID，可为空
     * @param userId    用户 ID，可为空
     * @return 归一化后的会话 ID
     */
    public String resolveSessionId(String sessionId, String userId) {
        if (hasText(sessionId)) {
            return sessionId.trim();
        }
        if (hasText(userId)) {
            return userId.trim();
        }
        return DEFAULT_USER_ID;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
