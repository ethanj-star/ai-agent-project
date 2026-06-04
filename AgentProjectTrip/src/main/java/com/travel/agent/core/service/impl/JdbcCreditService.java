package com.travel.agent.core.service.impl;

import com.travel.agent.core.service.CreditService;
import com.travel.agent.core.service.UserContextResolver;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * JDBC 版生成额度服务。
 *
 * <p>系统架构位置：RequirementController -> CreditService -> <b>JdbcCreditService</b> -> MySQL credit_accounts</p>
 *
 * <p>职责：
 * <ul>
 *   <li>把第五阶段生成额度从内存迁移到 MySQL。</li>
 *   <li>新用户默认创建 3 次开发额度。</li>
 *   <li>使用条件更新扣减额度，避免并发重复点击导致负数额度。</li>
 * </ul>
 * </p>
 */
@Service
@ConditionalOnProperty(name = "travel.persistence.type", havingValue = "jdbc")
@SuppressWarnings({"SqlResolve", "SqlNoDataSourceInspection"})
public class JdbcCreditService implements CreditService {

    /** 开发阶段每个用户默认赠送的完整生成次数。 */
    private static final int DEFAULT_CREDITS = 3;

    /** 执行 MySQL 读写的 Spring JDBC 模板。 */
    private final JdbcTemplate jdbcTemplate;

    /** 开发期把 sessionId 解析成 userId 的统一工具。 */
    private final UserContextResolver userContextResolver;

    /**
     * 构造 JDBC 额度服务。
     *
     * @param jdbcTemplate        MySQL JDBC 模板
     * @param userContextResolver 用户上下文解析器
     */
    public JdbcCreditService(JdbcTemplate jdbcTemplate, UserContextResolver userContextResolver) {
        this.jdbcTemplate = jdbcTemplate;
        this.userContextResolver = userContextResolver;
    }

    /**
     * 尝试消耗一次生成额度。
     *
     * <p>处理流程：
     * <ol>
     *   <li>确保用户额度账户存在。</li>
     *   <li>用 {@code remaining_credits > 0} 条件更新原子扣减。</li>
     *   <li>更新行数为 0 时表示额度不足。</li>
     * </ol>
     * </p>
     *
     * @param sessionId 会话或用户标识
     * @return 消耗成功返回 true，额度不足返回 false
     */
    @Override
    @Transactional
    public boolean consumeGenerationCredit(String sessionId) {
        String userId = resolveUserId(sessionId);
        ensureAccount(userId);
        int updated = jdbcTemplate.update("""
                        UPDATE credit_accounts
                        SET remaining_credits = remaining_credits - 1,
                            consumed_credits = consumed_credits + 1,
                            updated_at = CURRENT_TIMESTAMP
                        WHERE user_id = ? AND remaining_credits > 0
                        """,
                userId);
        return updated > 0;
    }

    /**
     * 退还一次生成额度。
     *
     * <p>用于 Graph 失败后的补偿。这里不处理订单，只恢复开发期额度计数。</p>
     *
     * @param sessionId 会话或用户标识
     */
    @Override
    @Transactional
    public void refundGenerationCredit(String sessionId) {
        String userId = resolveUserId(sessionId);
        ensureAccount(userId);
        jdbcTemplate.update("""
                        UPDATE credit_accounts
                        SET remaining_credits = remaining_credits + 1,
                            consumed_credits = GREATEST(consumed_credits - 1, 0),
                            updated_at = CURRENT_TIMESTAMP
                        WHERE user_id = ?
                        """,
                userId);
    }

    /**
     * 查询剩余额度。
     *
     * @param sessionId 会话或用户标识
     * @return 剩余可生成次数
     */
    @Override
    public int getRemainingCredits(String sessionId) {
        String userId = resolveUserId(sessionId);
        ensureAccount(userId);
        try {
            Integer remaining = jdbcTemplate.queryForObject(
                    "SELECT remaining_credits FROM credit_accounts WHERE user_id = ?",
                    Integer.class,
                    userId);
            return remaining;
        } catch (EmptyResultDataAccessException e) {
            return 0;
        }
    }

    private void ensureAccount(String userId) {
        jdbcTemplate.update("""
                        INSERT INTO credit_accounts (user_id, remaining_credits, consumed_credits)
                        VALUES (?, ?, 0)
                        ON DUPLICATE KEY UPDATE user_id = user_id
                        """,
                userId,
                DEFAULT_CREDITS);
    }

    private String resolveUserId(String sessionId) {
        return userContextResolver.resolveUserId(null, sessionId);
    }
}
