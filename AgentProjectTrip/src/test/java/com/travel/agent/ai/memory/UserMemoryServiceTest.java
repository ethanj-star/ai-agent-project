package com.travel.agent.ai.memory;

import com.travel.agent.ai.graph.model.MemoryScope;
import com.travel.agent.ai.graph.model.MemorySource;
import com.travel.agent.ai.graph.model.MemoryType;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import com.travel.agent.ai.graph.model.UserMemory;
import com.travel.agent.ai.graph.store.InMemoryUserMemoryStore;
import com.travel.agent.core.service.UserContextResolver;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * UserMemoryService 的单元测试。
 *
 * <p>重点验证第七阶段记忆写入策略：确认需求表只生成短期记忆，手动写入长期记忆可被压缩进 Planner 上下文。</p>
 */
class UserMemoryServiceTest {

    /**
     * 确认需求表后，应同步本次旅行短期记忆。
     */
    @Test
    void syncFromConfirmedRequirementWritesShortTermMemories() {
        InMemoryUserMemoryStore store = new InMemoryUserMemoryStore();
        UserMemoryService service = new UserMemoryService(store, new UserContextResolver());
        TravelRequirementSpec spec = requirementSpec();

        service.syncFromConfirmedRequirement(spec);

        assertThat(store.findActiveByUserIdAndScope("s1", MemoryScope.SHORT_TERM))
                .extracting(UserMemory::getKey)
                .contains("destinations", "budget", "accommodationPreference", "avoidances");
    }

    /**
     * 重复同步同一张需求表时，不应重复写入相同 key/value 的短期记忆。
     */
    @Test
    void syncFromConfirmedRequirementSkipsDuplicateMemories() {
        InMemoryUserMemoryStore store = new InMemoryUserMemoryStore();
        UserMemoryService service = new UserMemoryService(store, new UserContextResolver());
        TravelRequirementSpec spec = requirementSpec();

        service.syncFromConfirmedRequirement(spec);
        service.syncFromConfirmedRequirement(spec);

        assertThat(store.findActiveByUserIdAndScope("s1", MemoryScope.SHORT_TERM))
                .extracting(memory -> memory.getKey() + "=" + memory.getValue())
                .doesNotHaveDuplicates();
    }

    /**
     * 长期记忆应能被压缩成 Planner 可读上下文。
     */
    @Test
    void buildPromptContextIncludesLongTermMemory() {
        InMemoryUserMemoryStore store = new InMemoryUserMemoryStore();
        UserMemoryService service = new UserMemoryService(store, new UserContextResolver());
        UserMemory memory = new UserMemory();
        memory.setUserId("s1");
        memory.setSessionId("s1");
        memory.setScope(MemoryScope.LONG_TERM);
        memory.setType(MemoryType.PREFERENCE);
        memory.setKey("accommodationPreference");
        memory.setValue("不住青旅");
        memory.setSource(MemorySource.USER_EXPLICIT);

        service.save(memory);

        assertThat(service.buildPromptContext(null, "s1"))
                .contains("LONG_TERM")
                .contains("accommodationPreference")
                .contains("不住青旅");
    }

    private static TravelRequirementSpec requirementSpec() {
        TravelRequirementSpec spec = new TravelRequirementSpec();
        spec.setRequirementId("req-1");
        spec.setSessionId("s1");
        spec.setDestinations(List.of("法国", "意大利"));
        spec.setBudgetAmount(new BigDecimal("1200"));
        spec.setBudgetCurrency("EUR");
        spec.setAccommodationPreference("经济型酒店");
        spec.setAvoidances(List.of("避开人多"));
        return spec;
    }
}
