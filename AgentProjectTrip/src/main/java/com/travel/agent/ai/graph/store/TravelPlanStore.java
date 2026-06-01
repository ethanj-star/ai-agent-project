package com.travel.agent.ai.graph.store;

import com.travel.agent.ai.graph.model.TravelPlanRecord;
import com.travel.agent.ai.graph.model.TravelPlanVersion;

import java.util.Optional;

/**
 * 旅行计划版本仓库接口。
 *
 * <p>系统架构位置：RequirementController / PlanController -> <b>TravelPlanStore</b> -> 内存 / 数据库实现</p>
 *
 * <p>职责：
 * <ul>
 *   <li>保存完整生成后的 TravelPlanRecord。</li>
 *   <li>按 planId 查询当前计划和指定版本。</li>
 *   <li>为第六阶段自然语言修改闭环提供版本化存储边界。</li>
 * </ul>
 * </p>
 */
public interface TravelPlanStore {

    /**
     * 保存或覆盖计划记录。
     *
     * @param record 计划主记录
     * @return 保存后的计划记录
     */
    TravelPlanRecord save(TravelPlanRecord record);

    /**
     * 按 planId 查询计划。
     *
     * @param planId 计划 ID
     * @return 找到时返回计划记录
     */
    Optional<TravelPlanRecord> findById(String planId);

    /**
     * 给计划追加一个新版本。
     *
     * @param planId  计划 ID
     * @param version 新版本
     * @return 更新后的计划记录
     */
    Optional<TravelPlanRecord> addVersion(String planId, TravelPlanVersion version);

    /**
     * 查询计划指定版本。
     *
     * @param planId         计划 ID
     * @param versionNumber 版本号
     * @return 找到时返回版本记录
     */
    Optional<TravelPlanVersion> findVersion(String planId, int versionNumber);
}
