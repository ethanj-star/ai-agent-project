package com.travel.agent.ai.graph.store;

import com.travel.agent.ai.graph.model.MemoryScope;
import com.travel.agent.ai.graph.model.UserMemory;

import java.util.List;
import java.util.Optional;

/**
 * 用户记忆仓库接口。
 *
 * <p>系统架构位置：MemoryController / UserMemoryService -> <b>UserMemoryStore</b> -> 内存 / MySQL 实现</p>
 *
 * <p>职责：
 * <ul>
 *   <li>保存用户短期和长期记忆。</li>
 *   <li>按 userId、scope 和 active 状态读取 Planner 可用记忆。</li>
 *   <li>通过软删除禁用记忆，避免用户不可控的黑箱长期记忆。</li>
 * </ul>
 * </p>
 */
public interface UserMemoryStore {

    /**
     * 保存或覆盖一条用户记忆。
     *
     * @param memory 用户记忆
     * @return 保存后的用户记忆
     */
    UserMemory save(UserMemory memory);

    /**
     * 根据记忆 ID 查询。
     *
     * @param memoryId 记忆 ID
     * @return 找到时返回记忆
     */
    Optional<UserMemory> findById(String memoryId);

    /**
     * 查询用户所有生效记忆。
     *
     * @param userId 用户 ID
     * @return 生效记忆列表
     */
    List<UserMemory> findActiveByUserId(String userId);

    /**
     * 查询用户指定作用域的生效记忆。
     *
     * @param userId 用户 ID
     * @param scope  记忆作用域
     * @return 生效记忆列表
     */
    List<UserMemory> findActiveByUserIdAndScope(String userId, MemoryScope scope);

    /**
     * 禁用一条记忆。
     *
     * <p>第一版不物理删除，方便审计和后续恢复。</p>
     *
     * @param memoryId 记忆 ID
     */
    void deactivate(String memoryId);
}
