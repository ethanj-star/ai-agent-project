package com.travel.agent.ai.graph.model;

import java.util.ArrayList;
import java.util.List;

/**
 * 旅行需求表校验结果（Graph 层 - 生成门控依据）。
 *
 * <p>系统架构位置：RequirementValidationNode -> <b>RequirementValidation</b> -> RequirementController / GenerationGate</p>
 *
 * <p>职责：
 * <ul>
 *   <li>记录需求表是否已经具备生成完整行程的最低条件。</li>
 *   <li>把缺失字段、阻塞原因和非阻塞警告分开，方便前端展示和后续自动补全。</li>
 *   <li>为扣费前的工程门控提供稳定、可测试的判断结果。</li>
 * </ul>
 * </p>
 */
public class RequirementValidation {

    /** 是否已经满足完整生成所需的最低字段要求。 */
    private boolean complete;

    /** 是否可以进入用户确认步骤。 */
    private boolean readyToConfirm;

    /** 缺失字段名，前端可以据此高亮表单项。 */
    private List<String> missingFields = new ArrayList<>();

    /** 非阻塞警告，例如预算偏低或时间仍然模糊。 */
    private List<String> warnings = new ArrayList<>();

    /** 阻塞生成的原因，用于向用户解释为什么不能直接扣费生成。 */
    private List<String> blockingReasons = new ArrayList<>();

    public boolean isComplete() {
        return complete;
    }

    public void setComplete(boolean complete) {
        this.complete = complete;
    }

    public boolean isReadyToConfirm() {
        return readyToConfirm;
    }

    public void setReadyToConfirm(boolean readyToConfirm) {
        this.readyToConfirm = readyToConfirm;
    }

    public List<String> getMissingFields() {
        return missingFields;
    }

    public void setMissingFields(List<String> missingFields) {
        // 校验结果对前端展示很直接，空列表比 null 更容易渲染。
        this.missingFields = missingFields == null ? new ArrayList<>() : missingFields;
    }

    public List<String> getWarnings() {
        return warnings;
    }

    public void setWarnings(List<String> warnings) {
        // warning 是非阻塞提醒，没有提醒时仍返回空列表。
        this.warnings = warnings == null ? new ArrayList<>() : warnings;
    }

    public List<String> getBlockingReasons() {
        return blockingReasons;
    }

    public void setBlockingReasons(List<String> blockingReasons) {
        // 阻塞原因为空表示可以进入确认或生成流程。
        this.blockingReasons = blockingReasons == null ? new ArrayList<>() : blockingReasons;
    }
}
