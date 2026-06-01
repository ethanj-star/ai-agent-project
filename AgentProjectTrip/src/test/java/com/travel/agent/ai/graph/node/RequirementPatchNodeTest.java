package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.RequirementPatch;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RequirementPatchNode 的单元测试。
 *
 * <p>重点验证第六阶段核心需求变更可以合并进需求表副本，而不破坏当前计划绑定的旧需求表。</p>
 */
class RequirementPatchNodeTest {

    private final RequirementPatchNode node = new RequirementPatchNode();

    /**
     * 预算、住宿偏好和新增目的地应被合并到新需求表。
     */
    @Test
    void applyMergesBudgetAccommodationAndDestinations() {
        TravelRequirementSpec current = currentSpec();
        RequirementPatch patch = new RequirementPatch();
        patch.setBudgetAmount(BigDecimal.valueOf(900));
        patch.setBudgetCurrency("EUR");
        patch.setAccommodationPreference("经济型酒店");
        patch.setDestinations(List.of("瑞士"));

        TravelRequirementSpec updated = node.apply(current, patch);

        assertThat(updated.getBudgetAmount()).isEqualByComparingTo("900");
        assertThat(updated.getBudgetCurrency()).isEqualTo("EUR");
        assertThat(updated.getAccommodationPreference()).isEqualTo("经济型酒店");
        assertThat(updated.getDestinations()).containsExactly("法国", "意大利", "瑞士");
        assertThat(current.getBudgetAmount()).isEqualByComparingTo("1200");
        assertThat(current.getDestinations()).containsExactly("法国", "意大利");
    }

    /**
     * 偏好集合应支持追加和移除。
     */
    @Test
    void applyUpdatesPreferenceCollections() {
        TravelRequirementSpec current = currentSpec();
        current.setPreferences(List.of("小众", "博物馆"));
        RequirementPatch patch = new RequirementPatch();
        patch.setAddPreferences(List.of("美食"));
        patch.setRemovePreferences(List.of("博物馆"));
        patch.setAddAvoidances(List.of("不要徒步"));

        TravelRequirementSpec updated = node.apply(current, patch);

        assertThat(updated.getPreferences()).containsExactly("小众", "美食");
        assertThat(updated.getAvoidances()).contains("避开人多", "不要徒步");
    }

    private static TravelRequirementSpec currentSpec() {
        TravelRequirementSpec spec = new TravelRequirementSpec();
        spec.setRequirementId("req-1");
        spec.setSessionId("s1");
        spec.setDestinations(List.of("法国", "意大利"));
        spec.setBudgetAmount(BigDecimal.valueOf(1200));
        spec.setBudgetCurrency("EUR");
        spec.setAvoidances(List.of("避开人多"));
        return spec;
    }
}
