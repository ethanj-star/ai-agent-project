package com.travel.agent.ai.agents;

import com.travel.agent.ai.graph.model.AdaptiveRagDecision;
import com.travel.agent.ai.graph.model.RagQueryType;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * RAG 查询类型分类 Agent（AI 层 - Adaptive RAG 前置判断）。
 *
 * <p>系统架构位置：AdaptiveRagNode -> <b>RagQueryClassifierAgent</b> -> AdaptiveRagService</p>
 *
 * <p>职责：
 * <ul>
 *   <li>读取用户原始问题、结构化需求表、目的地、行程天数和偏好。</li>
 *   <li>用低成本规则判断本次 RAG 属于事实、探索、比较、指导还是多跳规划。</li>
 *   <li>输出 {@link AdaptiveRagDecision} 的查询类型和解释，后续由服务层补全检索策略。</li>
 * </ul>
 * </p>
 *
 * <p>第一版故意不直接调用模型：分类规则足够稳定时可以节省 token；
 * TODO(stage14-adaptive-rag-model-fallback)：后续如果规则分类不稳，再接 DeepSeek Flash 做低成本兜底分类。</p>
 */
@Component
public class RagQueryClassifierAgent {

    /**
     * 根据当前 Graph 状态判断 RAG 查询类型。
     *
     * <p>读取字段：userQuery、destinations、keywords、durationDays、requirementSpec。
     * 写入字段：不直接修改 state，只返回决策对象，避免分类 Agent 产生隐藏副作用。</p>
     *
     * @param state 当前旅行规划状态
     * @return 包含 queryType 和分类原因的 AdaptiveRagDecision
     */
    public AdaptiveRagDecision classify(TravelPlanState state) {
        AdaptiveRagDecision decision = new AdaptiveRagDecision();
        String text = normalizeText(state);

        if (!hasText(text)) {
            decision.setQueryType(RagQueryType.FACT_BASED);
            decision.setReason("输入文本为空或结构化信息不足，按事实型短问题处理。");
            return decision;
        }

        if (containsAny(text, "哪个更", "哪个比较", "比较", "对比", "区别", "差别", "versus", " vs ")) {
            decision.setQueryType(RagQueryType.COMPARATIVE);
            decision.setReason("文本包含比较意图，需要分别检索多个对象后再组织对比上下文。");
            return decision;
        }

        if (containsAny(text, "怎么买", "如何", "怎么准备", "怎么申请", "怎么预约", "怎么订",
                "步骤", "流程", "攻略步骤", "签证", "门票", "预约", "退税")) {
            decision.setQueryType(RagQueryType.INSTRUCTIONAL);
            decision.setReason("文本包含操作指导或流程类诉求，需要优先检索步骤型攻略和结构化说明。");
            return decision;
        }

        if (looksLikeCompleteTripPlanning(state, text)) {
            decision.setQueryType(RagQueryType.MULTI_HOP);
            decision.setReason("存在多目的地、行程天数、预算、时间或交通等组合约束，需要多阶段检索。");
            return decision;
        }

        if (containsAny(text, "推荐", "有哪些", "哪里", "小众", "冷门", "适合", "值得去",
                "慢游", "深度游", "拍照", "亲子", "情侣", "美食", "徒步", "避开人多")) {
            decision.setQueryType(RagQueryType.EXPLORATORY);
            decision.setReason("文本包含探索推荐或风格化偏好，需要做语义扩展和多样化召回。");
            return decision;
        }

        decision.setQueryType(RagQueryType.FACT_BASED);
        decision.setReason("没有明显规划、比较或操作指导信号，按事实型检索处理。");
        return decision;
    }

    /**
     * 判断用户是否在要求完整旅行规划。
     *
     * <p>完整规划通常同时出现目的地、天数、预算、时间或交通约束。即使用户没有说“帮我规划”，
     * 结构化需求表已经填好时，也应走 MULTI_HOP，而不是只查一条攻略。</p>
     */
    private static boolean looksLikeCompleteTripPlanning(TravelPlanState state, String text) {
        int destinationCount = destinationCount(state);
        Integer durationDays = durationDays(state);
        boolean hasDuration = durationDays != null && durationDays > 1
                || containsAny(text, "天", "一周", "两周", "day", "days");
        boolean hasBudget = containsAny(text, "预算", "欧", "人民币", "英镑", "eur", "cny", "gbp", "usd");
        boolean hasPlanningVerb = containsAny(text, "规划", "安排", "行程", "路线", "怎么玩", "串联");
        boolean hasTimeOrTransport = containsAny(text, "出发", "国庆", "暑假", "圣诞", "机票", "火车", "交通", "酒店");

        return (destinationCount >= 2 && (hasDuration || hasBudget || hasPlanningVerb))
                || (hasPlanningVerb && hasDuration && (hasBudget || hasTimeOrTransport))
                || (state != null && state.getRequirementSpec() != null && hasDuration && destinationCount > 0 && hasBudget);
    }

    private static int destinationCount(TravelPlanState state) {
        if (state == null) {
            return 0;
        }
        if (state.getRequirementSpec() != null && state.getRequirementSpec().getDestinations() != null
                && !state.getRequirementSpec().getDestinations().isEmpty()) {
            return state.getRequirementSpec().getDestinations().size();
        }
        return state.getDestinations() == null ? 0 : state.getDestinations().size();
    }

    private static Integer durationDays(TravelPlanState state) {
        if (state == null) {
            return null;
        }
        TravelRequirementSpec spec = state.getRequirementSpec();
        if (spec != null && spec.getDurationDays() != null) {
            return spec.getDurationDays();
        }
        return state.getDurationDays();
    }

    private static String normalizeText(TravelPlanState state) {
        if (state == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        addIfPresent(parts, state.getUserQuery());
        addIfPresent(parts, state.getTravelTime());
        addAll(parts, state.getDestinations());
        addAll(parts, state.getKeywords());

        TravelRequirementSpec spec = state.getRequirementSpec();
        if (spec != null) {
            addIfPresent(parts, spec.getOriginalMessage());
            addIfPresent(parts, spec.getDepartureCity());
            addIfPresent(parts, spec.getStartDateText());
            addIfPresent(parts, spec.getTravelStyle());
            addIfPresent(parts, spec.getAccommodationPreference());
            addIfPresent(parts, spec.getTransportPreference());
            addIfPresent(parts, spec.getSpecialNotes());
            addAll(parts, spec.getDestinations());
            addAll(parts, spec.getPreferences());
            addAll(parts, spec.getAvoidances());
            if (spec.getBudgetAmount() != null) {
                parts.add("预算 " + spec.getBudgetAmount());
            }
        }
        return String.join(" ", parts).toLowerCase(Locale.ROOT);
    }

    private static void addAll(List<String> parts, List<String> values) {
        if (values == null) {
            return;
        }
        for (String value : values) {
            addIfPresent(parts, value);
        }
    }

    private static void addIfPresent(List<String> parts, String value) {
        if (hasText(value)) {
            parts.add(value.trim());
        }
    }

    private static boolean containsAny(String text, String... markers) {
        if (!hasText(text) || markers == null) {
            return false;
        }
        for (String marker : markers) {
            if (hasText(marker) && text.contains(marker.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
