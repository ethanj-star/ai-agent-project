package com.travel.agent.ai.graph.store;

import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * InMemoryRequirementStore 的单元测试。
 *
 * <p>验证第五阶段需求表在内存仓库中的保存、读取和删除行为。</p>
 */
class InMemoryRequirementStoreTest {

    /**
     * 仓库应按 requirementId 保存和读取需求表。
     */
    @Test
    void saveAndFindById() {
        InMemoryRequirementStore store = new InMemoryRequirementStore();
        TravelRequirementSpec spec = new TravelRequirementSpec();
        spec.setRequirementId("req-1");

        store.save(spec);

        assertThat(store.findById("req-1")).containsSame(spec);
    }

    /**
     * requirementId 为空时不应写入仓库。
     */
    @Test
    void saveRejectsBlankRequirementId() {
        InMemoryRequirementStore store = new InMemoryRequirementStore();

        assertThatThrownBy(() -> store.save(new TravelRequirementSpec()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requirementId");
    }

    /**
     * 删除后再次查询应为空。
     */
    @Test
    void deleteRemovesSpec() {
        InMemoryRequirementStore store = new InMemoryRequirementStore();
        TravelRequirementSpec spec = new TravelRequirementSpec();
        spec.setRequirementId("req-1");
        store.save(spec);

        store.delete("req-1");

        assertThat(store.findById("req-1")).isEmpty();
    }
}
