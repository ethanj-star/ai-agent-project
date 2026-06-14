package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.RequirementPatch;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 结构化需求补丁合并节点（Graph 层 - 第六阶段需求变更桥梁）。
 *
 * <p>系统架构位置：PlanModificationAgent -> <b>RequirementPatchNode</b> -> RequirementValidationNode</p>
 *
 * <p>职责：
 * <ul>
 *   <li>读取用户修改指令中抽取出的 {@link RequirementPatch}。</li>
 *   <li>把预算、天数、目的地、住宿偏好等核心变更合并到 {@link TravelRequirementSpec} 副本。</li>
 *   <li>不直接生成新计划，而是把更新后的需求表交回第五阶段确认流程。</li>
 * </ul>
 * </p>
 */
@Component
public class RequirementPatchNode {

    /**
     * 将需求补丁应用到需求表副本。
     *
     * <p>处理流程：
     * <ol>
     *   <li>复制原需求表，避免用户未确认前破坏当前计划记录。</li>
     *   <li>对非空补丁字段执行覆盖或集合追加 / 移除。</li>
     *   <li>返回更新后的需求表，调用方随后应执行 RequirementValidationNode。</li>
     * </ol>
     * </p>
     *
     * @param current 当前计划绑定的需求表
     * @param patch   用户修改抽取出的补丁
     * @return 应用补丁后的需求表副本
     */
    public TravelRequirementSpec apply(TravelRequirementSpec current, RequirementPatch patch) {
        // 先复制当前需求表，避免用户未确认变更前污染已生成计划绑定的原始需求。
        TravelRequirementSpec updated = copy(current);
        if (patch == null) {
            return updated;
        }

        // 目的地采用追加合并，而不是直接覆盖；用户说“再加瑞士”时不能丢掉原有法国/意大利。
        if (patch.getDestinations() != null && !patch.getDestinations().isEmpty()) {
            updated.setDestinations(mergeUnique(updated.getDestinations(), patch.getDestinations()));
        }
        if (hasText(patch.getDepartureCity())) {
            updated.setDepartureCity(patch.getDepartureCity().trim());
        }
        if (hasText(patch.getStartDateText())) {
            updated.setStartDateText(patch.getStartDateText().trim());
        }
        if (patch.getDurationDays() != null) {
            updated.setDurationDays(patch.getDurationDays());
        }
        if (patch.getTravelerCount() != null) {
            updated.setTravelerCount(patch.getTravelerCount());
        }
        if (patch.getBudgetAmount() != null) {
            updated.setBudgetAmount(patch.getBudgetAmount());
        }
        if (hasText(patch.getBudgetCurrency())) {
            updated.setBudgetCurrency(patch.getBudgetCurrency().trim());
        }
        if (patch.getBudgetIncludesInternationalFlight() != null) {
            updated.setBudgetIncludesInternationalFlight(patch.getBudgetIncludesInternationalFlight());
        }
        if (hasText(patch.getAccommodationPreference())) {
            updated.setAccommodationPreference(patch.getAccommodationPreference().trim());
        }
        if (hasText(patch.getTransportPreference())) {
            updated.setTransportPreference(patch.getTransportPreference().trim());
        }

        // 偏好和避开项支持增删，适合“多加美食”“不要徒步”这类局部语义。
        updated.setPreferences(applyCollectionPatch(
                updated.getPreferences(), patch.getAddPreferences(), patch.getRemovePreferences()));
        updated.setAvoidances(applyCollectionPatch(
                updated.getAvoidances(), patch.getAddAvoidances(), patch.getRemoveAvoidances()));
        return updated;
    }

    private static TravelRequirementSpec copy(TravelRequirementSpec source) {
        TravelRequirementSpec target = new TravelRequirementSpec();
        if (source == null) {
            return target;
        }
        // 手动复制是为了保持模型对象简单，不引入深拷贝框架；集合 setter 内部会做 null-safe 处理。
        target.setRequirementId(source.getRequirementId());
        target.setSessionId(source.getSessionId());
        target.setOriginalMessage(source.getOriginalMessage());
        target.setDestinations(source.getDestinations());
        target.setDepartureCity(source.getDepartureCity());
        target.setStartDateText(source.getStartDateText());
        target.setStartDate(source.getStartDate());
        target.setDurationDays(source.getDurationDays());
        target.setTravelerCount(source.getTravelerCount());
        target.setBudgetAmount(source.getBudgetAmount());
        target.setBudgetCurrency(source.getBudgetCurrency());
        target.setBudgetIncludesInternationalFlight(source.getBudgetIncludesInternationalFlight());
        target.setPreferences(source.getPreferences());
        target.setAvoidances(source.getAvoidances());
        target.setTravelStyle(source.getTravelStyle());
        target.setAccommodationPreference(source.getAccommodationPreference());
        target.setTransportPreference(source.getTransportPreference());
        target.setSpecialNotes(source.getSpecialNotes());
        target.setStatus(source.getStatus());
        target.setMissingFields(source.getMissingFields());
        target.setWarnings(source.getWarnings());
        return target;
    }

    private static List<String> applyCollectionPatch(List<String> current,
                                                     List<String> additions,
                                                     List<String> removals) {
        Set<String> values = new LinkedHashSet<>();
        // LinkedHashSet 既去重又保留用户原来的偏好顺序。
        if (current != null) {
            values.addAll(current);
        }
        if (removals != null) {
            // 先删后加，允许用户同一轮里“不要 X，增加 Y”。
            values.removeAll(removals);
        }
        if (additions != null) {
            values.addAll(additions);
        }
        return new ArrayList<>(values);
    }

    private static List<String> mergeUnique(List<String> current, List<String> additions) {
        Set<String> values = new LinkedHashSet<>();
        if (current != null) {
            values.addAll(current);
        }
        if (additions != null) {
            values.addAll(additions);
        }
        return new ArrayList<>(values);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
