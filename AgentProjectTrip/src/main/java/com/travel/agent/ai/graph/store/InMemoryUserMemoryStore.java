package com.travel.agent.ai.graph.store;

import com.travel.agent.ai.graph.model.MemoryScope;
import com.travel.agent.ai.graph.model.UserMemory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版用户记忆仓库。
 *
 * <p>系统架构位置：UserMemoryService -> UserMemoryStore -> <b>InMemoryUserMemoryStore</b></p>
 *
 * <p>职责：
 * <ul>
 *   <li>为本地测试和 memory 模式提供轻量记忆保存能力。</li>
 *   <li>支持按 userId / scope 查询生效记忆。</li>
 *   <li>通过 active=false 模拟软删除，保持与 JDBC 实现一致的行为。</li>
 * </ul>
 * </p>
 *
 * <p>注意：内存实现会在应用重启后丢失数据，只适合开发兜底和单元测试。</p>
 */
@Component
@ConditionalOnProperty(name = "travel.persistence.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryUserMemoryStore implements UserMemoryStore {

    /** memoryId -> UserMemory 的单机内存表。 */
    private final Map<String, UserMemory> store = new ConcurrentHashMap<>();

    /**
     * 保存或覆盖用户记忆。
     *
     * @param memory 用户记忆
     * @return 保存后的用户记忆
     */
    @Override
    public UserMemory save(UserMemory memory) {
        if (memory == null || !hasText(memory.getMemoryId())) {
            throw new IllegalArgumentException("memoryId must not be blank");
        }
        if (!hasText(memory.getUserId())) {
            throw new IllegalArgumentException("userId must not be blank");
        }
        // 每次保存都刷新 updatedAt，和 JDBC 实现的更新时间语义保持一致。
        memory.setUpdatedAt(Instant.now());
        store.put(memory.getMemoryId(), memory);
        return memory;
    }

    /**
     * 根据记忆 ID 查询。
     *
     * @param memoryId 记忆 ID
     * @return 找到时返回记忆
     */
    @Override
    public Optional<UserMemory> findById(String memoryId) {
        if (!hasText(memoryId)) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(memoryId));
    }

    /**
     * 查询某个用户的所有生效记忆。
     *
     * @param userId 用户 ID
     * @return 生效记忆列表
     */
    @Override
    public List<UserMemory> findActiveByUserId(String userId) {
        if (!hasText(userId)) {
            return List.of();
        }
        return store.values().stream()
                .filter(memory -> memory != null && memory.isActive())
                .filter(memory -> userId.equals(memory.getUserId()))
                // 按创建时间升序，构造 prompt 时能保持用户偏好出现的自然顺序。
                .sorted(Comparator.comparing(UserMemory::getCreatedAt))
                .toList();
    }

    /**
     * 查询某个用户指定作用域的生效记忆。
     *
     * @param userId 用户 ID
     * @param scope  记忆作用域
     * @return 生效记忆列表
     */
    @Override
    public List<UserMemory> findActiveByUserIdAndScope(String userId, MemoryScope scope) {
        if (!hasText(userId) || scope == null) {
            return List.of();
        }
        List<UserMemory> result = new ArrayList<>();
        for (UserMemory memory : findActiveByUserId(userId)) {
            if (scope == memory.getScope()) {
                result.add(memory);
            }
        }
        return result;
    }

    /**
     * 禁用指定记忆。
     *
     * @param memoryId 记忆 ID
     */
    @Override
    public void deactivate(String memoryId) {
        findById(memoryId).ifPresent(memory -> {
            // 记忆采用软删除，方便后续审计“这个偏好为什么不再生效”。
            memory.setActive(false);
            memory.setUpdatedAt(Instant.now());
        });
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
