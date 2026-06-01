package com.travel.agent.ai.graph.model;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * 旅行计划主记录。
 *
 * <p>系统架构位置：RequirementController / PlanController -> <b>TravelPlanRecord</b> -> TravelPlanStore</p>
 *
 * <p>职责：
 * <ul>
 *   <li>把一次已确认需求生成出的完整旅行计划绑定到稳定 planId。</li>
 *   <li>保存当前结构化需求表快照和所有历史版本。</li>
 *   <li>支撑第六阶段“围绕 planId 多轮自然语言修改”的协作闭环。</li>
 * </ul>
 * </p>
 */
public class TravelPlanRecord {

    /** 计划 ID，后续查询、修改和版本读取都围绕它。 */
    private String planId;

    /** 来源需求表 ID。 */
    private String requirementId;

    /** 所属 session 或用户标识。 */
    private String sessionId;

    /** 当前计划对应的结构化需求表快照。 */
    private TravelRequirementSpec requirementSpec;

    /** 当前最新版本号。 */
    private int currentVersion = 1;

    /** 历史版本列表。 */
    private List<TravelPlanVersion> versions = new ArrayList<>();

    /** 计划创建时间。 */
    private Instant createdAt = Instant.now();

    /** 最近更新时间。 */
    private Instant updatedAt = Instant.now();

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public String getRequirementId() {
        return requirementId;
    }

    public void setRequirementId(String requirementId) {
        this.requirementId = requirementId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public TravelRequirementSpec getRequirementSpec() {
        return requirementSpec;
    }

    public void setRequirementSpec(TravelRequirementSpec requirementSpec) {
        this.requirementSpec = requirementSpec;
    }

    public int getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(int currentVersion) {
        this.currentVersion = Math.max(1, currentVersion);
    }

    public List<TravelPlanVersion> getVersions() {
        return versions;
    }

    public void setVersions(List<TravelPlanVersion> versions) {
        this.versions = versions == null ? new ArrayList<>() : versions;
        this.currentVersion = currentVersionFromVersions(this.versions);
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt == null ? Instant.now() : createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt == null ? Instant.now() : updatedAt;
    }

    /**
     * 添加一个新版本并更新 currentVersion / updatedAt。
     *
     * @param version 新版本记录
     */
    public void addVersion(TravelPlanVersion version) {
        if (version == null) {
            return;
        }
        versions.add(version);
        currentVersion = Math.max(currentVersion, version.getVersion());
        updatedAt = Instant.now();
    }

    /**
     * 读取当前版本。
     *
     * @return 当前版本存在时返回版本记录
     */
    public Optional<TravelPlanVersion> current() {
        return version(currentVersion);
    }

    /**
     * 按版本号读取版本。
     *
     * @param versionNumber 版本号
     * @return 找到时返回对应版本
     */
    public Optional<TravelPlanVersion> version(int versionNumber) {
        if (versions == null || versions.isEmpty()) {
            return Optional.empty();
        }
        return versions.stream()
                .filter(version -> version != null && version.getVersion() == versionNumber)
                .findFirst();
    }

    private static int currentVersionFromVersions(List<TravelPlanVersion> versions) {
        if (versions == null || versions.isEmpty()) {
            return 1;
        }
        return versions.stream()
                .filter(version -> version != null)
                .max(Comparator.comparingInt(TravelPlanVersion::getVersion))
                .map(TravelPlanVersion::getVersion)
                .orElse(1);
    }
}
