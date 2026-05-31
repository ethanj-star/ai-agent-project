package com.travel.agent.ai.graph.node;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * DurationParser 的单元测试。
 *
 * <p>重点验证常见中文旅行时长表达能被稳定解析为 durationDays / durationText。</p>
 */
class DurationParserTest {

    /**
     * keywords 中的“10天”应被识别为 10 天。
     */
    @Test
    void extractParsesArabicDaysFromKeywords() {
        DurationParser.DurationResult result =
                DurationParser.extract("法国和意大利", null, List.of("10天", "避开人多"));

        assertThat(result.durationDays()).isEqualTo(10);
        assertThat(result.durationText()).isEqualTo("10天");
    }

    /**
     * “5晚6天”应以天数部分作为标准 durationDays。
     */
    @Test
    void extractParsesNightDayExpression() {
        DurationParser.DurationResult result =
                DurationParser.extract("想去法国5晚6天", null, List.of());

        assertThat(result.durationDays()).isEqualTo(6);
        assertThat(result.durationText()).isEqualTo("5晚6天");
    }

    /**
     * “一周左右”应归一化为 7 天，同时保留原始表达。
     */
    @Test
    void extractParsesChineseWeekExpression() {
        DurationParser.DurationResult result =
                DurationParser.extract("我想去意大利一周左右", null, List.of());

        assertThat(result.durationDays()).isEqualTo(7);
        assertThat(result.durationText()).isEqualTo("一周左右");
    }

    /**
     * 已识别为时长的关键词应从偏好关键词中移除。
     */
    @Test
    void removeDurationKeywordsKeepsOnlyRealPreferences() {
        List<String> result = DurationParser.removeDurationKeywords(List.of("10天", "预算1200欧", "避开人多"));

        assertThat(result).containsExactly("预算1200欧", "避开人多");
    }
}
