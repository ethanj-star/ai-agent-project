package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.dto.GatekeeperResponse;
import com.travel.agent.ai.graph.model.GraphInputRequest;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import com.travel.agent.ai.graph.model.WorkflowStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * 状态初始化节点（Graph 层 - 入口状态转换）。
 *
 * <p>系统架构位置：LangGraphPlannerFacade → <b>InitStateNode</b> → TravelPlanState</p>
 *
 * <p>职责：
 * <ul>
 *   <li>把 MastermindAgent 传入的 {@link GraphInputRequest} 转换为工作流共享状态。</li>
 *   <li>从 Gatekeeper 的 entities 中提取目的地、出行时间、行程时长和关键词。</li>
 *   <li>第五阶段开始，若请求携带已确认的 {@link TravelRequirementSpec}，则优先按需求表初始化状态。</li>
 *   <li>把“10天”“一周左右”等时长表达写入 duration 字段，避免误当成出发时间。</li>
 *   <li>统一处理 null 和空集合，保证后续节点可以安全读取状态。</li>
 * </ul>
 * </p>
 */
@Component
public class InitStateNode {

    /**
     * 初始化旅行规划状态。
     *
     * <p>处理流程：
     * <ol>
     *   <li>创建空的 {@link TravelPlanState}。</li>
     *   <li>写入用户原始输入和 Gatekeeper 路由结果。</li>
     *   <li>从 entities 中提取 locations、time、duration、keywords。</li>
     *   <li>对缺失字段写入空列表或“未指定”，避免后续节点空指针。</li>
     * </ol>
     * </p>
     *
     * @param request Graph 入口请求，可为空
     * @return 初始化后的 TravelPlanState
     */
    public TravelPlanState init(GraphInputRequest request) {
        TravelPlanState state = new TravelPlanState();

        // 防御性兜底：即使调用方传空请求，也返回可继续流转的最小状态
        if (request == null) {
            state.setTravelTime("未指定");
            state.setWorkflowStatus(WorkflowStatus.PLANNING);
            return state;
        }

        state.setSessionId(request.getSessionId());
        state.setUserQuery(request.getUserQuery());
        state.setLastUserMessage(request.getUserQuery());
        state.setRoute(request.getRoute());

        // 第五阶段以后，结构化需求表比 Gatekeeper 实体更可靠；有 spec 时优先按强类型字段初始化。
        if (request.getRequirementSpec() != null) {
            initFromRequirementSpec(state, request.getRequirementSpec());
            return state;
        }

        // 旧入口仍依赖 Gatekeeper entities；这里兼容没有结构化需求表的 PLAN_OR_RAG 请求。
        GatekeeperResponse.Entities entities = request.getRoute() == null
                ? null
                : request.getRoute().getEntities();

        List<String> keywords = safeList(entities == null ? null : entities.getKeywords());
        String rawTravelTime = entities == null ? null : entities.getTime();
        // 独立解析 duration，避免 “10天” 被当成出发日期写入 travelTime。
        DurationParser.DurationResult duration =
                DurationParser.extract(request.getUserQuery(), rawTravelTime, keywords);

        state.setDestinations(safeList(entities == null ? null : entities.getLocations()));
        state.setTravelTime(resolveTravelTime(rawTravelTime));
        state.setDurationDays(duration.durationDays());
        state.setDurationText(duration.durationText());
        state.setKeywords(DurationParser.removeDurationKeywords(keywords));
        state.setSuccess(false);
        state.setWorkflowStatus(WorkflowStatus.PLANNING);
        state.setTurnCount(1);

        return state;
    }

    /**
     * 从第五阶段已确认的结构化需求表初始化状态。
     *
     * <p>处理流程：
     * <ol>
     *   <li>把 requirementSpec 写入状态，供后续 Planner / RiskReasoning 作为优先事实来源。</li>
     *   <li>从需求表复制目的地、出行时间、行程时长和偏好关键词。</li>
     *   <li>保留 GraphInputRequest 中合成的 userQuery，方便 RAG 和日志仍能看到完整需求摘要。</li>
     * </ol>
     * </p>
     */
    private static void initFromRequirementSpec(TravelPlanState state, TravelRequirementSpec spec) {
        // requirementSpec 保留完整强类型事实，后续 Planner/RiskReasoning 应优先读取它。
        state.setRequirementSpec(spec);
        state.setDestinations(safeList(spec.getDestinations()));
        state.setTravelTime(resolveRequirementTravelTime(spec));
        state.setDurationDays(spec.getDurationDays());
        state.setDurationText(spec.getDurationDays() == null ? null : spec.getDurationDays() + "天");
        state.setKeywords(buildRequirementKeywords(spec));
        state.setSuccess(false);
        state.setWorkflowStatus(WorkflowStatus.PLANNING);
        state.setTurnCount(1);
    }

    private static String resolveRequirementTravelTime(TravelRequirementSpec spec) {
        if (spec == null) {
            return "未指定";
        }
        if (hasText(spec.getStartDateText())) {
            return spec.getStartDateText().trim();
        }
        return spec.getStartDate() == null ? "未指定" : spec.getStartDate().toString();
    }

    private static List<String> buildRequirementKeywords(TravelRequirementSpec spec) {
        List<String> keywords = new ArrayList<>();
        if (spec == null) {
            return keywords;
        }
        // 把强类型需求同步成 keywords，是为了兼容仍依赖 keywords 的分支派发和部分校验规则。
        if (spec.getBudgetAmount() != null) {
            keywords.add("预算" + spec.getBudgetAmount().stripTrailingZeros().toPlainString()
                    + defaultText(spec.getBudgetCurrency(), ""));
        }
        if (spec.getBudgetIncludesInternationalFlight() != null) {
            keywords.add(spec.getBudgetIncludesInternationalFlight() ? "包含国际机票" : "不含国际机票");
        }
        if (spec.getTravelerCount() != null) {
            keywords.add(spec.getTravelerCount() + "人");
        }
        if (hasText(spec.getDepartureCity())) {
            keywords.add("出发地" + spec.getDepartureCity().trim());
        }
        keywords.addAll(safeList(spec.getPreferences()));
        keywords.addAll(safeList(spec.getAvoidances()));
        if (hasText(spec.getTravelStyle())) {
            keywords.add(spec.getTravelStyle().trim());
        }
        if (hasText(spec.getAccommodationPreference())) {
            keywords.add(spec.getAccommodationPreference().trim());
        }
        if (hasText(spec.getTransportPreference())) {
            keywords.add(spec.getTransportPreference().trim());
        }
        if (hasText(spec.getSpecialNotes())) {
            keywords.add(spec.getSpecialNotes().trim());
        }
        return keywords;
    }

    /**
     * 解析出真正的出发时间。
     *
     * <p>Gatekeeper 可能把“10天”误放进 time 字段；这种值应交给 duration，
     * travelTime 则保持“未指定”，表示用户还没有给出具体日期或月份。</p>
     */
    private static String resolveTravelTime(String rawTravelTime) {
        if (!hasText(rawTravelTime) || DurationParser.isDurationExpression(rawTravelTime)) {
            return "未指定";
        }
        return rawTravelTime.trim();
    }

    /**
     * 清洗大模型提取出的字符串列表。
     *
     * <p>Gatekeeper 可能返回 null、空字符串或带空格的值；这里统一转换为干净列表，
     * 避免后续 prompt 中出现无意义的空元素。</p>
     */
    private static List<String> safeList(List<String> values) {
        if (values == null || values.isEmpty()) {
            return new ArrayList<>();
        }

        List<String> cleaned = new ArrayList<>();
        for (String value : values) {
            if (hasText(value)) {
                cleaned.add(value.trim());
            }
        }
        return cleaned;
    }

    /**
     * 判断字符串是否包含非空白文本。
     */
    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private static String defaultText(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }
}
