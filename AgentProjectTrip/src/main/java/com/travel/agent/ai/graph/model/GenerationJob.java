package com.travel.agent.ai.graph.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 异步旅行方案生成任务记录。
 *
 * <p>系统架构位置：RequirementController -> AsyncPlanGenerationService -> <b>GenerationJob</b> -> GenerationJobStore</p>
 *
 * <p>职责：
 * <ul>
 *   <li>记录一次完整规划生成任务从创建、运行到成功或失败的全过程。</li>
 *   <li>把 requirementId、planId、任务状态、阶段、错误和扣费结果关联到同一条可追踪记录。</li>
 *   <li>作为前端轮询接口和后续任务队列、重试、取消能力的基础模型。</li>
 * </ul>
 * </p>
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class GenerationJob {

    /** 任务 ID，前端轮询和数据库主键都围绕它。 */
    private String jobId;

    /** 用户 ID；开发阶段通常由 sessionId 映射而来。 */
    private String userId;

    /** 会话 ID，用于把任务和当前调试台会话关联。 */
    private String sessionId;

    /** 本任务要生成的结构化需求表 ID。 */
    private String requirementId;

    /** 生成成功后保存的旅行计划 ID；失败或未完成时为空。 */
    private String planId;

    /** 当前任务状态。 */
    private GenerationJobStatus status = GenerationJobStatus.PENDING;

    /** 当前任务阶段。 */
    private GenerationJobStage currentStage = GenerationJobStage.CREATED;

    /** 创建任务时的请求快照，主要用于审计和调试。 */
    private Map<String, Object> request = new LinkedHashMap<>();

    /** 任务结束后的结果摘要，完整计划正文仍以 TravelPlanStore 为准。 */
    private Map<String, Object> result = new LinkedHashMap<>();

    /** 失败时的错误摘要，前端可以直接展示。 */
    private String errorMessage;

    /** 最终是否保留了本次扣费；失败退款后应为 false。 */
    private boolean creditCharged;

    /** 任务创建时间。 */
    private Instant createdAt = Instant.now();

    /** 任务最近更新时间。 */
    private Instant updatedAt = Instant.now();

    /** 任务进入终态的时间。 */
    private Instant finishedAt;

    public String getJobId() {
        return jobId;
    }

    public void setJobId(String jobId) {
        this.jobId = jobId;
    }

    public String getUserId() {
        return userId;
    }

    public void setUserId(String userId) {
        this.userId = userId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public String getRequirementId() {
        return requirementId;
    }

    public void setRequirementId(String requirementId) {
        this.requirementId = requirementId;
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
        // 反序列化旧数据或测试传 null 时回到 PENDING，表示任务尚未真正开始。
        this.status = status == null ? GenerationJobStatus.PENDING : status;
    }

    public GenerationJobStage getCurrentStage() {
        return currentStage;
    }

    public void setCurrentStage(GenerationJobStage currentStage) {
        // 阶段为空时退回 CREATED，保证前端进度条至少有一个可展示起点。
        this.currentStage = currentStage == null ? GenerationJobStage.CREATED : currentStage;
    }

    public Map<String, Object> getRequest() {
        return request;
    }

    public void setRequest(Map<String, Object> request) {
        // 快照用拷贝保存，避免外部 Map 后续修改影响任务审计记录。
        this.request = request == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request);
    }

    public Map<String, Object> getResult() {
        return result;
    }

    public void setResult(Map<String, Object> result) {
        // 结果摘要同样拷贝，任务完成后的展示数据应保持稳定。
        this.result = result == null ? new LinkedHashMap<>() : new LinkedHashMap<>(result);
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public boolean isCreditCharged() {
        return creditCharged;
    }

    public void setCreditCharged(boolean creditCharged) {
        this.creditCharged = creditCharged;
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

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    /**
     * 更新任务运行阶段。
     *
     * @param stage 当前执行阶段
     */
    public void markRunning(GenerationJobStage stage) {
        // 每次阶段推进都刷新 updatedAt，轮询接口可以看到任务仍在活动。
        setStatus(GenerationJobStatus.RUNNING);
        setCurrentStage(stage);
        setUpdatedAt(Instant.now());
    }

    /**
     * 把任务标记为成功终态。
     *
     * @param planId 生成成功后保存的计划 ID
     * @param result 任务结果摘要
     */
    public void markSucceeded(String planId, Map<String, Object> result) {
        // 成功终态固定落在 FINISHED 阶段，并清空旧错误信息。
        setStatus(GenerationJobStatus.SUCCEEDED);
        setCurrentStage(GenerationJobStage.FINISHED);
        setPlanId(planId);
        setResult(result);
        setErrorMessage(null);
        setFinishedAt(Instant.now());
        setUpdatedAt(getFinishedAt());
    }

    /**
     * 把任务标记为失败终态。
     *
     * @param errorMessage 失败原因摘要
     * @param result       任务结果摘要，可为空
     */
    public void markFailed(String errorMessage, Map<String, Object> result) {
        // 失败也进入 FINISHED 阶段，前端据 status 区分成功还是失败。
        setStatus(GenerationJobStatus.FAILED);
        setCurrentStage(GenerationJobStage.FINISHED);
        setErrorMessage(errorMessage);
        setResult(result);
        setFinishedAt(Instant.now());
        setUpdatedAt(getFinishedAt());
    }
}
