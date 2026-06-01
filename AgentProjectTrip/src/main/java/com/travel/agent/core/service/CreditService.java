package com.travel.agent.core.service;

/**
 * 生成额度服务接口。
 *
 * <p>系统架构位置：RequirementController / GenerationGate -> <b>CreditService</b> -> 支付或额度系统</p>
 *
 * <p>职责：
 * <ul>
 *   <li>在第五阶段为“确认后再生成”的付费门控提供抽象接口。</li>
 *   <li>第一版先用内存免费额度模拟，后续可替换为真实账户、订单和支付系统。</li>
 * </ul>
 * </p>
 */
public interface CreditService {

    /**
     * 尝试消耗一次生成额度。
     *
     * @param sessionId 会话或用户标识
     * @return 消耗成功返回 true，额度不足返回 false
     */
    boolean consumeGenerationCredit(String sessionId);

    /**
     * 退还一次生成额度。
     *
     * <p>用于 Graph 生成失败且产品策略要求退还时。</p>
     *
     * @param sessionId 会话或用户标识
     */
    void refundGenerationCredit(String sessionId);

    /**
     * 查询剩余额度。
     *
     * @param sessionId 会话或用户标识
     * @return 剩余可生成次数
     */
    int getRemainingCredits(String sessionId);
}
