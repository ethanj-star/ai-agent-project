package com.travel.agent.core.service.impl;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * InMemoryCreditService 的单元测试。
 *
 * <p>验证第五阶段模拟额度门控：完整生成消耗额度，失败可退还。</p>
 */
class InMemoryCreditServiceTest {

    /**
     * 每个 session 默认有 3 次生成额度，消耗完后应拒绝继续生成。
     */
    @Test
    void consumeCreditsUntilEmpty() {
        InMemoryCreditService service = new InMemoryCreditService();

        assertThat(service.getRemainingCredits("s1")).isEqualTo(3);
        assertThat(service.consumeGenerationCredit("s1")).isTrue();
        assertThat(service.consumeGenerationCredit("s1")).isTrue();
        assertThat(service.consumeGenerationCredit("s1")).isTrue();
        assertThat(service.consumeGenerationCredit("s1")).isFalse();
        assertThat(service.getRemainingCredits("s1")).isZero();
    }

    /**
     * 生成失败时可以退还一次额度。
     */
    @Test
    void refundReturnsOneCredit() {
        InMemoryCreditService service = new InMemoryCreditService();

        assertThat(service.consumeGenerationCredit("s1")).isTrue();
        service.refundGenerationCredit("s1");

        assertThat(service.getRemainingCredits("s1")).isEqualTo(3);
    }
}
