package com.travel.agent.core.service.impl;

import com.travel.agent.core.service.CreditService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 内存版生成额度服务。
 *
 * <p>系统架构位置：RequirementController -> CreditService -> <b>InMemoryCreditService</b></p>
 *
 * <p>职责：
 * <ul>
 *   <li>为第五阶段第一版模拟“每次完整生成消耗一次额度”的门控能力。</li>
 *   <li>默认每个 session 提供 3 次生成额度，需求抽取和表单补全不消耗额度。</li>
 *   <li>后续接入真实支付系统时，可以替换为数据库和订单驱动的实现。</li>
 * </ul>
 * </p>
 */
@Service
@ConditionalOnProperty(name = "travel.persistence.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryCreditService implements CreditService {

    /** 开发阶段每个 session 默认赠送的完整生成次数。 */
    private static final int DEFAULT_CREDITS = 3;

    /** sessionId -> 剩余额度。 */
    private final Map<String, AtomicInteger> credits = new ConcurrentHashMap<>();

    /**
     * 消耗一次生成额度。
     *
     * <p>处理流程：
     * <ol>
     *   <li>按 sessionId 初始化默认额度。</li>
     *   <li>剩余额度大于 0 时原子扣减。</li>
     *   <li>额度不足时返回 false，Controller 不会进入高成本 Graph 流程。</li>
     * </ol>
     * </p>
     *
     * @param sessionId 会话或用户标识
     * @return 扣减成功返回 true
     */
    @Override
    public boolean consumeGenerationCredit(String sessionId) {
        AtomicInteger counter = credits.computeIfAbsent(normalizeSessionId(sessionId),
                ignored -> new AtomicInteger(DEFAULT_CREDITS));
        while (true) {
            int current = counter.get();
            if (current <= 0) {
                return false;
            }
            if (counter.compareAndSet(current, current - 1)) {
                return true;
            }
        }
    }

    /**
     * 退还一次生成额度。
     *
     * @param sessionId 会话或用户标识
     */
    @Override
    public void refundGenerationCredit(String sessionId) {
        credits.computeIfAbsent(normalizeSessionId(sessionId), ignored -> new AtomicInteger(DEFAULT_CREDITS))
                .incrementAndGet();
    }

    /**
     * 查询剩余额度。
     *
     * @param sessionId 会话或用户标识
     * @return 当前剩余生成次数
     */
    @Override
    public int getRemainingCredits(String sessionId) {
        return credits.computeIfAbsent(normalizeSessionId(sessionId),
                        ignored -> new AtomicInteger(DEFAULT_CREDITS))
                .get();
    }

    private static String normalizeSessionId(String sessionId) {
        return sessionId == null || sessionId.isBlank() ? "anonymous-session" : sessionId.trim();
    }
}
