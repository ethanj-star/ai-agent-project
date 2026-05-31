package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.dto.GatekeeperResponse;
import com.travel.agent.ai.graph.model.GraphInputRequest;
import com.travel.agent.ai.graph.model.TravelPlanState;
import com.travel.agent.ai.graph.model.WorkflowStatus;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 用户补充信息合并节点。
 *
 * <p>系统架构位置：ConversationStateStore -> <b>MergeClarificationNode</b> -> RetrieveKnowledgeNode</p>
 *
 * <p>职责：
 * <ul>
 *   <li>读取上一轮保存的 pending 状态。</li>
 *   <li>把用户当前输入作为补充信息合并进原始任务。</li>
 *   <li>优先使用当前 Gatekeeper 提取到的目的地、时间、时长和关键词刷新结构化字段。</li>
 *   <li>清理 pending 问题，让工作流回到 PLANNING 状态继续续跑。</li>
 * </ul>
 * </p>
 */
@Component
public class MergeClarificationNode {

    /**
     * 合并旧 pending 状态和当前用户补充输入。
     *
     * @param pendingState 上一轮需要澄清时保存的状态
     * @param request      当前请求
     * @return 可继续进入 RAG / Planner 的新状态
     */
    public TravelPlanState merge(TravelPlanState pendingState, GraphInputRequest request) {
        TravelPlanState state = pendingState == null ? new TravelPlanState() : pendingState;
        String currentMessage = request == null ? null : request.getUserQuery();

        state.setSessionId(resolveSessionId(request, state));
        state.setLastUserMessage(currentMessage);
        state.setUserQuery(mergeUserQuery(state.getUserQuery(), currentMessage));
        state.setRoute(request == null || request.getRoute() == null ? state.getRoute() : request.getRoute());
        state.setDestinations(resolveDestinations(state, request));
        state.setTravelTime(resolveTravelTime(state, request));
        DurationParser.DurationResult duration = resolveDuration(state, request);
        state.setDurationDays(duration.durationDays());
        state.setDurationText(duration.durationText());
        state.setKeywords(resolveKeywords(state, request));
        state.setClarificationAnswers(appendAnswer(state.getClarificationAnswers(), currentMessage));

        state.setPendingQuestions(new ArrayList<>());
        state.setValidationIssues(new ArrayList<>());
        state.setBranchTasks(new ArrayList<>());
        state.setBranchResults(new ArrayList<>());
        state.setDraft(null);
        state.setFinalAnswer(null);
        state.setErrorMessage(null);
        state.setSuccess(false);
        state.setWorkflowStatus(WorkflowStatus.PLANNING);
        state.setTurnCount(Math.max(1, state.getTurnCount()) + 1);
        return state;
    }

    /**
     * 将原始需求和当前补充拼接成 Planner 可理解的完整上下文。
     */
    private static String mergeUserQuery(String oldQuery, String currentMessage) {
        if (!hasText(oldQuery)) {
            return hasText(currentMessage) ? currentMessage.trim() : "";
        }
        if (!hasText(currentMessage)) {
            return oldQuery.trim();
        }
        return oldQuery.trim() + "\n用户补充信息：" + currentMessage.trim();
    }

    private static String resolveSessionId(GraphInputRequest request, TravelPlanState state) {
        if (request != null && hasText(request.getSessionId())) {
            return request.getSessionId().trim();
        }
        return state == null ? null : state.getSessionId();
    }

    private static List<String> resolveDestinations(TravelPlanState state, GraphInputRequest request) {
        List<String> newLocations = safeList(entities(request) == null ? null : entities(request).getLocations());
        if (!newLocations.isEmpty()) {
            return newLocations;
        }
        return state == null ? new ArrayList<>() : safeList(state.getDestinations());
    }

    private static String resolveTravelTime(TravelPlanState state, GraphInputRequest request) {
        String newTime = entities(request) == null ? null : entities(request).getTime();
        if (hasText(newTime) && !DurationParser.isDurationExpression(newTime)) {
            return newTime.trim();
        }
        return state == null || !hasText(state.getTravelTime()) ? "未指定" : state.getTravelTime();
    }

    private static DurationParser.DurationResult resolveDuration(TravelPlanState state, GraphInputRequest request) {
        DurationParser.DurationResult newDuration = DurationParser.extract(
                request == null ? null : request.getUserQuery(),
                entities(request) == null ? null : entities(request).getTime(),
                safeList(entities(request) == null ? null : entities(request).getKeywords()));
        if (newDuration.present()) {
            return newDuration;
        }
        if (state != null && state.getDurationDays() != null && hasText(state.getDurationText())) {
            return new DurationParser.DurationResult(state.getDurationDays(), state.getDurationText());
        }
        return DurationParser.DurationResult.empty();
    }

    private static List<String> resolveKeywords(TravelPlanState state, GraphInputRequest request) {
        Set<String> merged = new LinkedHashSet<>();
        merged.addAll(DurationParser.removeDurationKeywords(state == null ? List.of() : safeList(state.getKeywords())));
        merged.addAll(DurationParser.removeDurationKeywords(
                safeList(entities(request) == null ? null : entities(request).getKeywords())));
        return new ArrayList<>(merged);
    }

    private static List<String> appendAnswer(List<String> oldAnswers, String currentMessage) {
        List<String> answers = new ArrayList<>(oldAnswers == null ? List.of() : oldAnswers);
        if (hasText(currentMessage)) {
            answers.add(currentMessage.trim());
        }
        return answers;
    }

    private static GatekeeperResponse.Entities entities(GraphInputRequest request) {
        if (request == null || request.getRoute() == null) {
            return null;
        }
        return request.getRoute().getEntities();
    }

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
}
