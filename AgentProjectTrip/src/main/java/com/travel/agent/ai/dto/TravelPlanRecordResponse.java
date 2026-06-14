package com.travel.agent.ai.dto;

import com.travel.agent.ai.graph.model.TravelPlanRecord;
import com.travel.agent.ai.graph.model.TravelPlanVersion;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;

/**
 * 旅行计划记录响应 DTO。
 *
 * <p>系统架构位置：PlanController -> <b>TravelPlanRecordResponse</b> -> 前端</p>
 *
 * <p>职责：
 * <ul>
 *   <li>返回 planId、当前版本、当前答案和结构化需求表。</li>
 *   <li>避免前端必须理解 TravelPlanRecord 内部版本列表结构。</li>
 * </ul>
 * </p>
 */
public class TravelPlanRecordResponse {

    /** 计划 ID。 */
    private String planId;

    /** 来源需求表 ID。 */
    private String requirementId;

    /** 当前最新版本号。 */
    private int currentVersion;

    /** 当前版本答案。 */
    private String currentAnswer;

    /** 当前计划绑定的结构化需求表。 */
    private TravelRequirementSpec requirementSpec;

    public static TravelPlanRecordResponse from(TravelPlanRecord record) {
        // Controller 使用这个方法把带历史版本列表的内部记录，整理成“当前计划概览”。
        TravelPlanRecordResponse response = new TravelPlanRecordResponse();
        if (record == null) {
            return response;
        }
        response.setPlanId(record.getPlanId());
        response.setRequirementId(record.getRequirementId());
        response.setCurrentVersion(record.getCurrentVersion());
        response.setRequirementSpec(record.getRequirementSpec());
        // currentAnswer 只取当前版本的 finalAnswer，历史版本由单独的版本接口查询。
        response.setCurrentAnswer(record.current()
                .map(TravelPlanVersion::getFinalAnswer)
                .orElse(null));
        return response;
    }

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

    public int getCurrentVersion() {
        return currentVersion;
    }

    public void setCurrentVersion(int currentVersion) {
        this.currentVersion = currentVersion;
    }

    public String getCurrentAnswer() {
        return currentAnswer;
    }

    public void setCurrentAnswer(String currentAnswer) {
        this.currentAnswer = currentAnswer;
    }

    public TravelRequirementSpec getRequirementSpec() {
        return requirementSpec;
    }

    public void setRequirementSpec(TravelRequirementSpec requirementSpec) {
        this.requirementSpec = requirementSpec;
    }
}
