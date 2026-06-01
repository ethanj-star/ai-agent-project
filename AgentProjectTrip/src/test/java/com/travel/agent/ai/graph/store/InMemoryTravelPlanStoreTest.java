package com.travel.agent.ai.graph.store;

import com.travel.agent.ai.graph.model.TravelPlanRecord;
import com.travel.agent.ai.graph.model.TravelPlanVersion;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * InMemoryTravelPlanStore 的单元测试。
 *
 * <p>验证第六阶段计划记录保存、版本追加和指定版本查询能力。</p>
 */
class InMemoryTravelPlanStoreTest {

    /**
     * 仓库应按 planId 保存和读取计划。
     */
    @Test
    void saveAndFindById() {
        InMemoryTravelPlanStore store = new InMemoryTravelPlanStore();
        TravelPlanRecord record = new TravelPlanRecord();
        record.setPlanId("plan-1");

        store.save(record);

        assertThat(store.findById("plan-1")).containsSame(record);
    }

    /**
     * 空 planId 不应写入仓库。
     */
    @Test
    void saveRejectsBlankPlanId() {
        InMemoryTravelPlanStore store = new InMemoryTravelPlanStore();

        assertThatThrownBy(() -> store.save(new TravelPlanRecord()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("planId");
    }

    /**
     * 追加版本后应更新 currentVersion，并可按版本号读取。
     */
    @Test
    void addVersionUpdatesCurrentVersion() {
        InMemoryTravelPlanStore store = new InMemoryTravelPlanStore();
        TravelPlanRecord record = new TravelPlanRecord();
        record.setPlanId("plan-1");
        store.save(record);

        TravelPlanVersion version = new TravelPlanVersion();
        version.setVersion(2);
        version.setFinalAnswer("v2 answer");
        store.addVersion("plan-1", version);

        TravelPlanRecord saved = store.findById("plan-1").orElseThrow();
        assertThat(saved.getCurrentVersion()).isEqualTo(2);
        assertThat(store.findVersion("plan-1", 2)).containsSame(version);
    }
}
