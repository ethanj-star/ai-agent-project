package com.travel.agent.ai.graph.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * 计划修改意图识别结果。
 *
 * <p>系统架构位置：PlanModificationAgent -> <b>PlanModificationDecision</b> -> PlanController</p>
 *
 * <p>职责：
 * <ul>
 *   <li>承载第六阶段对用户修改指令的结构化判断。</li>
 *   <li>告诉调度层应该进入局部重写、需求表更新、追问还是普通回复。</li>
 *   <li>在核心需求变更时携带 {@link RequirementPatch}，供 RequirementPatchNode 合并。</li>
 * </ul>
 * </p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class PlanModificationDecision {

    /** 修改意图类型。 */
    private PlanModificationIntent intent = PlanModificationIntent.UNSUPPORTED;

    /** 目标日期，例如“第3天”；为空表示不限定具体日期。 */
    private String targetDay;

    /** 目标模块，例如 itinerary、budget、risk、accommodation。 */
    private List<String> targetSections = new ArrayList<>();

    /** 压缩后的修改要求，用于 prompt 和版本摘要。 */
    private String instructionSummary;

    /** 核心需求变更补丁；非 REQUIREMENT_CHANGE 时通常为空。 */
    private RequirementPatch requirementPatch;

    /** 是否需要用户重新确认。 */
    private boolean requiresConfirmation;

    /** 需要追问时的问题。 */
    private String clarificationQuestion;

    public PlanModificationIntent getIntent() {
        return intent;
    }

    public void setIntent(PlanModificationIntent intent) {
        // 模型没给出意图时使用 UNSUPPORTED，Controller 会返回更明确的用户提示。
        this.intent = intent == null ? PlanModificationIntent.UNSUPPORTED : intent;
    }

    public String getTargetDay() {
        return targetDay;
    }

    public void setTargetDay(String targetDay) {
        this.targetDay = targetDay;
    }

    public List<String> getTargetSections() {
        return targetSections;
    }

    public void setTargetSections(List<String> targetSections) {
        // 目标模块为空表示泛化修改；保持空列表方便后续 prompt 直接拼接。
        this.targetSections = targetSections == null ? new ArrayList<>() : targetSections;
    }

    public String getInstructionSummary() {
        return instructionSummary;
    }

    public void setInstructionSummary(String instructionSummary) {
        this.instructionSummary = instructionSummary;
    }

    public RequirementPatch getRequirementPatch() {
        return requirementPatch;
    }

    public void setRequirementPatch(RequirementPatch requirementPatch) {
        this.requirementPatch = requirementPatch;
    }

    public boolean isRequiresConfirmation() {
        return requiresConfirmation;
    }

    public void setRequiresConfirmation(boolean requiresConfirmation) {
        this.requiresConfirmation = requiresConfirmation;
    }

    public String getClarificationQuestion() {
        return clarificationQuestion;
    }

    public void setClarificationQuestion(String clarificationQuestion) {
        this.clarificationQuestion = clarificationQuestion;
    }
}
