package com.travel.agent.ai.dto;

import com.travel.agent.ai.graph.model.GenerationJob;
import com.travel.agent.ai.graph.model.GenerationJobStage;
import com.travel.agent.ai.graph.model.GenerationJobStatus;

/**
 * 异步生成任务创建响应 DTO。
 *
 * <p>系统架构位置：RequirementController -> AsyncPlanGenerationService -> <b>GenerationJobCreateResponse</b> -> 前端</p>
 *
 * <p>职责：
 * <ul>
 *   <li>在用户点击生成后立即返回 jobId，避免浏览器长时间等待完整规划结果。</li>
 *   <li>携带初始任务状态和阶段，前端可以据此开始轮询。</li>
 *   <li>区分新建任务和已存在运行中任务，方便解释重复点击保护。</li>
 * </ul>
 * </p>
 */
public class GenerationJobCreateResponse {

    /** 生成任务 ID。 */
    private String jobId;

    /** 来源需求表 ID。 */
    private String requirementId;

    /** 当前会话 ID。 */
    private String sessionId;

    /** 生成成功后的 planId；创建时通常为空。 */
    private String planId;

    /** 当前任务状态。 */
    private GenerationJobStatus status;

    /** 当前任务阶段。 */
    private GenerationJobStage currentStage;

    /** 是否返回了已有运行中任务。 */
    private boolean existing;

    /** 面向前端展示的提示语。 */
    private String assistantMessage;

    public static GenerationJobCreateResponse from(GenerationJob job, boolean existing) {
        // 把内部任务模型压平成前端创建任务后最关心的字段：jobId、状态和是否复用了旧任务。
        GenerationJobCreateResponse response = new GenerationJobCreateResponse();
        response.setJobId(job.getJobId());
        response.setRequirementId(job.getRequirementId());
        response.setSessionId(job.getSessionId());
        response.setPlanId(job.getPlanId());
        response.setStatus(job.getStatus());
        response.setCurrentStage(job.getCurrentStage());
        response.setExisting(existing);
        // 重复点击生成时不是报错，而是解释“已有任务在跑”，前端继续轮询同一个 jobId 即可。
        response.setAssistantMessage(existing
                ? "这张需求表已经有生成任务在运行，已返回现有 jobId。"
                : "已创建旅行方案生成任务，请稍等，我会在后台完成规划。");
        return response;
    }

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
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

    public String getPlanId() {
        return planId;
    }

    public void setPlanId(String planId) {
        this.planId = planId;
    }

    public GenerationJobStatus getStatus() {
        return status;
    }

    public void setStatus(GenerationJobStatus status) {
        this.status = status;
    }

    public GenerationJobStage getCurrentStage() {
        return currentStage;
    }

    public void setCurrentStage(GenerationJobStage currentStage) {
        this.currentStage = currentStage;
    }

    public boolean isExisting() {
        return existing;
    }

    public void setExisting(boolean existing) {
        this.existing = existing;
    }

    public String getAssistantMessage() {
        return assistantMessage;
    }

    public void setAssistantMessage(String assistantMessage) {
        this.assistantMessage = assistantMessage;
    }
}
