package com.travel.agent.ai.graph.node;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 行程时长解析器。
 *
 * <p>系统架构位置：InitStateNode / MergeClarificationNode -> <b>DurationParser</b> -> TravelPlanState</p>
 *
 * <p>职责：
 * <ul>
 *   <li>从用户原文、Gatekeeper time 和 keywords 中识别“10天”“5晚6天”“一周左右”等时长表达。</li>
 *   <li>把“旅行时长”和“出发时间”拆开，避免把 10 天误当成 travelTime。</li>
 *   <li>清理 keywords 中已经被识别为时长的词，让偏好列表更接近真实用户偏好。</li>
 * </ul>
 * </p>
 *
 * <p>第一版只输出单个 durationDays 和 durationText；范围表达、模糊精度后续再升级为更完整的对象。</p>
 */
final class DurationParser {

    private static final Pattern NIGHT_DAY_PATTERN =
            Pattern.compile("(\\d{1,2})\\s*晚\\s*(\\d{1,2})\\s*(?:天|日)\\s*(?:左右|上下)?");

    private static final Pattern ARABIC_DAY_PATTERN =
            Pattern.compile("(\\d{1,2})\\s*(?:天|日)\\s*(?:左右|上下)?");

    private static final Pattern ARABIC_WEEK_PATTERN =
            Pattern.compile("(\\d{1,2})\\s*(?:周|星期)\\s*(?:左右|上下)?");

    private static final Pattern CHINESE_DAY_PATTERN =
            Pattern.compile("([一二两三四五六七八九十]{1,3})\\s*(?:天|日)\\s*(?:左右|上下)?");

    private static final Pattern CHINESE_WEEK_PATTERN =
            Pattern.compile("([一二两三四五六七八九十]{1,3})\\s*(?:周|星期)\\s*(?:左右|上下)?");

    private DurationParser() {
    }

    /**
     * 从多个候选来源中提取行程时长。
     *
     * <p>优先级：keywords > time > userQuery。Gatekeeper 往往会把“10天”放进 keywords，
     * 因此先读 keywords 可以最大限度保留用户原始表达。</p>
     */
    static DurationResult extract(String userQuery, String travelTime, List<String> keywords) {
        // Gatekeeper 最常把“10天”放进 keywords，所以先读 keywords。
        DurationResult fromKeywords = extractFromKeywords(keywords);
        if (fromKeywords.present()) {
            return fromKeywords;
        }

        // 有时 Gatekeeper 会误把时长放进 time；这里第二优先级读取 travelTime 做纠偏。
        DurationResult fromTime = extractFromText(travelTime);
        if (fromTime.present()) {
            return fromTime;
        }

        // 最后再扫用户原文，覆盖 Gatekeeper 没有抽取到关键词的情况。
        return extractFromText(userQuery);
    }

    /**
     * 判断一段文本是否整体就是时长表达。
     *
     * <p>用于识别 Gatekeeper 把“10天”错误放入 time 字段的情况，此时 Init / Merge
     * 应该把它写入 duration，而不是 travelTime。</p>
     */
    static boolean isDurationExpression(String value) {
        if (!hasText(value)) {
            return false;
        }
        String text = value.trim();
        return fullMatch(NIGHT_DAY_PATTERN, text)
                || fullMatch(ARABIC_DAY_PATTERN, text)
                || fullMatch(ARABIC_WEEK_PATTERN, text)
                || fullMatch(CHINESE_DAY_PATTERN, text)
                || fullMatch(CHINESE_WEEK_PATTERN, text);
    }

    /**
     * 移除 keywords 中已经识别为时长的元素。
     */
    static List<String> removeDurationKeywords(List<String> keywords) {
        List<String> cleaned = new ArrayList<>();
        if (keywords == null || keywords.isEmpty()) {
            return cleaned;
        }
        for (String keyword : keywords) {
            // 时长已经写入 durationDays / durationText 后，就不要继续留在偏好 keywords 里干扰 Planner。
            if (hasText(keyword) && !isDurationExpression(keyword)) {
                cleaned.add(keyword.trim());
            }
        }
        return cleaned;
    }

    private static DurationResult extractFromKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return DurationResult.empty();
        }
        for (String keyword : keywords) {
            // 只取第一个明确时长表达，避免“10天 5晚6天”这类冲突输入造成状态不稳定。
            DurationResult result = extractFromText(keyword);
            if (result.present()) {
                return result;
            }
        }
        return DurationResult.empty();
    }

    private static DurationResult extractFromText(String text) {
        if (!hasText(text)) {
            return DurationResult.empty();
        }
        String value = text.trim();

        // 先匹配“5晚6天”，因为它同时包含两个数字，不能被普通“6天”规则提前截断。
        Matcher nightDayMatcher = NIGHT_DAY_PATTERN.matcher(value);
        if (nightDayMatcher.find()) {
            return new DurationResult(
                    Integer.parseInt(nightDayMatcher.group(2)),
                    nightDayMatcher.group().trim());
        }

        Matcher arabicWeekMatcher = ARABIC_WEEK_PATTERN.matcher(value);
        if (arabicWeekMatcher.find()) {
            return new DurationResult(
                    Integer.parseInt(arabicWeekMatcher.group(1)) * 7,
                    arabicWeekMatcher.group().trim());
        }

        Matcher chineseWeekMatcher = CHINESE_WEEK_PATTERN.matcher(value);
        if (chineseWeekMatcher.find()) {
            Integer weeks = chineseNumberToInt(chineseWeekMatcher.group(1));
            if (weeks != null) {
                return new DurationResult(weeks * 7, chineseWeekMatcher.group().trim());
            }
        }

        Matcher arabicDayMatcher = ARABIC_DAY_PATTERN.matcher(value);
        if (arabicDayMatcher.find()) {
            return new DurationResult(
                    Integer.parseInt(arabicDayMatcher.group(1)),
                    arabicDayMatcher.group().trim());
        }

        Matcher chineseDayMatcher = CHINESE_DAY_PATTERN.matcher(value);
        if (chineseDayMatcher.find()) {
            Integer days = chineseNumberToInt(chineseDayMatcher.group(1));
            if (days != null) {
                return new DurationResult(days, chineseDayMatcher.group().trim());
            }
        }

        return DurationResult.empty();
    }

    private static boolean fullMatch(Pattern pattern, String text) {
        Matcher matcher = pattern.matcher(text);
        return matcher.matches();
    }

    private static Integer chineseNumberToInt(String text) {
        if (!hasText(text)) {
            return null;
        }
        // 只支持一到十九这类旅行时长常见表达；超大中文数字不是当前规划入口的目标。
        String normalized = text.trim().replace("两", "二");
        if ("十".equals(normalized)) {
            return 10;
        }
        if (normalized.contains("十")) {
            String[] parts = normalized.split("十", -1);
            int tens = parts[0].isEmpty() ? 1 : chineseDigit(parts[0]);
            int ones = parts.length > 1 && !parts[1].isEmpty() ? chineseDigit(parts[1]) : 0;
            return tens <= 0 || ones < 0 ? null : tens * 10 + ones;
        }
        int digit = chineseDigit(normalized);
        return digit > 0 ? digit : null;
    }

    private static int chineseDigit(String text) {
        return switch (text) {
            case "一" -> 1;
            case "二" -> 2;
            case "三" -> 3;
            case "四" -> 4;
            case "五" -> 5;
            case "六" -> 6;
            case "七" -> 7;
            case "八" -> 8;
            case "九" -> 9;
            default -> -1;
        };
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    /**
     * 行程时长解析结果。
     *
     * @param durationDays 归一化后的天数
     * @param durationText 用户原始时长表达
     */
    record DurationResult(Integer durationDays, String durationText) {

        static DurationResult empty() {
            return new DurationResult(null, null);
        }

        boolean present() {
            return durationDays != null && hasText(durationText);
        }
    }
}
