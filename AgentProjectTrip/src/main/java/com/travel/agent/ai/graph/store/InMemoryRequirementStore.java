package com.travel.agent.ai.graph.store;

import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版结构化旅行需求表仓库。
 *
 * <p>系统架构位置：RequirementController -> RequirementStore -> <b>InMemoryRequirementStore</b></p>
 *
 * <p>职责：
 * <ul>
 *   <li>为第五阶段第一版提供零数据库依赖的需求表保存能力。</li>
 *   <li>支持按 requirementId 读取、覆盖和删除需求表。</li>
 *   <li>后续接入数据库时只需要替换本实现，不影响 Controller 和 Agent 编排代码。</li>
 * </ul>
 * </p>
 *
 * <p>注意：内存实现会在应用重启后丢失数据，仅适合开发和单机验证。</p>
 */
@Component
@ConditionalOnProperty(name = "travel.persistence.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryRequirementStore implements RequirementStore {

    /** requirementId -> TravelRequirementSpec 的单机内存表。 */
    private final Map<String, TravelRequirementSpec> store = new ConcurrentHashMap<>();

    /**
     * 保存或覆盖需求表。
     *
     * <p>异常策略：requirementId 为空属于调用方错误，直接抛出 IllegalArgumentException，
     * 避免把无法索引的数据写入仓库。</p>
     *
     * @param spec 结构化旅行需求表
     * @return 原样返回保存后的需求表
     */
    @Override
    public TravelRequirementSpec save(TravelRequirementSpec spec) {
        if (spec == null || spec.getRequirementId() == null || spec.getRequirementId().isBlank()) {
            throw new IllegalArgumentException("requirementId must not be blank");
        }
        store.put(spec.getRequirementId(), spec);
        return spec;
    }

    /**
     * 根据 requirementId 读取需求表。
     *
     * @param requirementId 需求表 ID
     * @return 找到时返回需求表，否则为空
     */
    @Override
    public Optional<TravelRequirementSpec> findById(String requirementId) {
        if (requirementId == null || requirementId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(requirementId));
    }

    /**
     * 删除指定需求表。
     *
     * @param requirementId 需求表 ID
     */
    @Override
    public void delete(String requirementId) {
        if (requirementId != null && !requirementId.isBlank()) {
            store.remove(requirementId);
        }
    }
}
