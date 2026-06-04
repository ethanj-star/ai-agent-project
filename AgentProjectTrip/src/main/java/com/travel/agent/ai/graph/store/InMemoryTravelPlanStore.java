package com.travel.agent.ai.graph.store;

import com.travel.agent.ai.graph.model.TravelPlanRecord;
import com.travel.agent.ai.graph.model.TravelPlanVersion;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版旅行计划版本仓库。
 *
 * <p>系统架构位置：PlanController -> TravelPlanStore -> <b>InMemoryTravelPlanStore</b></p>
 *
 * <p>职责：
 * <ul>
 *   <li>为第六阶段第一版提供无需数据库的计划记录保存和版本追加能力。</li>
 *   <li>支持按 planId 查询计划、追加版本、读取指定版本。</li>
 *   <li>后续接入数据库时可替换本实现，保持 Controller 和 Agent 逻辑不变。</li>
 * </ul>
 * </p>
 *
 * <p>注意：内存实现会在应用重启后丢失所有计划，仅用于开发验证。</p>
 */
@Component
@ConditionalOnProperty(name = "travel.persistence.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryTravelPlanStore implements TravelPlanStore {

    /** planId -> TravelPlanRecord 的单机内存表。 */
    private final Map<String, TravelPlanRecord> store = new ConcurrentHashMap<>();

    /**
     * 保存或覆盖计划记录。
     *
     * @param record 计划主记录
     * @return 保存后的计划记录
     */
    @Override
    public TravelPlanRecord save(TravelPlanRecord record) {
        if (record == null || record.getPlanId() == null || record.getPlanId().isBlank()) {
            throw new IllegalArgumentException("planId must not be blank");
        }
        store.put(record.getPlanId(), record);
        return record;
    }

    /**
     * 按 planId 查询计划。
     *
     * @param planId 计划 ID
     * @return 找到时返回计划记录
     */
    @Override
    public Optional<TravelPlanRecord> findById(String planId) {
        if (planId == null || planId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(planId));
    }

    /**
     * 给计划追加新版本。
     *
     * <p>这里直接修改内存对象并重新 put，数据库实现时可以替换为事务写入。</p>
     *
     * @param planId  计划 ID
     * @param version 新版本
     * @return 更新后的计划记录
     */
    @Override
    public Optional<TravelPlanRecord> addVersion(String planId, TravelPlanVersion version) {
        Optional<TravelPlanRecord> record = findById(planId);
        record.ifPresent(existing -> {
            existing.addVersion(version);
            store.put(existing.getPlanId(), existing);
        });
        return record;
    }

    /**
     * 查询指定版本。
     *
     * @param planId        计划 ID
     * @param versionNumber 版本号
     * @return 找到时返回版本记录
     */
    @Override
    public Optional<TravelPlanVersion> findVersion(String planId, int versionNumber) {
        return findById(planId).flatMap(record -> record.version(versionNumber));
    }
}
