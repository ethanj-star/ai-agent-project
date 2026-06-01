package com.travel.agent.ai.graph.store;

import com.travel.agent.ai.graph.model.TravelRequirementSpec;

import java.util.Optional;

/**
 * 结构化旅行需求表仓库接口。
 *
 * <p>系统架构位置：RequirementController -> <b>RequirementStore</b> -> 内存 / 数据库实现</p>
 *
 * <p>职责：
 * <ul>
 *   <li>保存第五阶段抽取出的旅行需求表。</li>
 *   <li>为确认、生成和后续自然语言修改提供按 requirementId 读取的稳定入口。</li>
 *   <li>隔离存储实现，第一版用内存，后续可以替换为数据库。</li>
 * </ul>
 * </p>
 */
public interface RequirementStore {

    /**
     * 保存或覆盖一张需求表。
     *
     * @param spec 结构化旅行需求表
     * @return 保存后的需求表
     */
    TravelRequirementSpec save(TravelRequirementSpec spec);

    /**
     * 根据需求表 ID 查找需求表。
     *
     * @param requirementId 需求表 ID
     * @return 找到时返回需求表，否则返回空
     */
    Optional<TravelRequirementSpec> findById(String requirementId);

    /**
     * 删除需求表。
     *
     * @param requirementId 需求表 ID
     */
    void delete(String requirementId);
}
