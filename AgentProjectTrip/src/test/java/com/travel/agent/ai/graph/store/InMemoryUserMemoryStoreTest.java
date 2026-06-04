package com.travel.agent.ai.graph.store;

import com.travel.agent.ai.graph.model.MemoryScope;
import com.travel.agent.ai.graph.model.UserMemory;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * InMemoryUserMemoryStore 的单元测试。
 *
 * <p>验证第七阶段用户记忆在内存仓库中的保存、按作用域查询和软删除行为。</p>
 */
class InMemoryUserMemoryStoreTest {

    /**
     * 仓库应按 memoryId 保存和读取记忆。
     */
    @Test
    void saveAndFindById() {
        InMemoryUserMemoryStore store = new InMemoryUserMemoryStore();
        UserMemory memory = memory("mem-1", "u1", MemoryScope.LONG_TERM, "住宿", "不住青旅");

        store.save(memory);

        assertThat(store.findById("mem-1")).containsSame(memory);
    }

    /**
     * memoryId 为空时不应写入仓库。
     */
    @Test
    void saveRejectsBlankMemoryId() {
        InMemoryUserMemoryStore store = new InMemoryUserMemoryStore();

        assertThatThrownBy(() -> store.save(new UserMemory()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("memoryId");
    }

    /**
     * 查询时应只返回指定用户和作用域的 active 记忆。
     */
    @Test
    void findActiveByUserIdAndScope() {
        InMemoryUserMemoryStore store = new InMemoryUserMemoryStore();
        store.save(memory("mem-1", "u1", MemoryScope.LONG_TERM, "住宿", "不住青旅"));
        store.save(memory("mem-2", "u1", MemoryScope.SHORT_TERM, "预算", "1200EUR"));
        store.save(memory("mem-3", "u2", MemoryScope.LONG_TERM, "交通", "火车"));

        assertThat(store.findActiveByUserIdAndScope("u1", MemoryScope.LONG_TERM))
                .extracting(UserMemory::getMemoryId)
                .containsExactly("mem-1");
    }

    /**
     * 禁用后该记忆不应再出现在 active 查询结果中。
     */
    @Test
    void deactivateHidesMemoryFromActiveQueries() {
        InMemoryUserMemoryStore store = new InMemoryUserMemoryStore();
        store.save(memory("mem-1", "u1", MemoryScope.LONG_TERM, "住宿", "不住青旅"));

        store.deactivate("mem-1");

        assertThat(store.findById("mem-1")).get().extracting(UserMemory::isActive).isEqualTo(false);
        assertThat(store.findActiveByUserId("u1")).isEmpty();
    }

    private static UserMemory memory(String memoryId,
                                     String userId,
                                     MemoryScope scope,
                                     String key,
                                     String value) {
        UserMemory memory = new UserMemory();
        memory.setMemoryId(memoryId);
        memory.setUserId(userId);
        memory.setSessionId(userId);
        memory.setScope(scope);
        memory.setKey(key);
        memory.setValue(value);
        return memory;
    }
}
