package com.travel.agent.ai.dto;

import com.travel.agent.ai.graph.model.GenerationJob;
import com.travel.agent.ai.graph.model.GenerationJobStage;
import com.travel.agent.ai.graph.model.GenerationJobStatus;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 异步生成任务查询响应 DTO。
 *
 * <p>系统架构位置：GenerationJobController -> GenerationJobStore -> <b>GenerationJobResponse</b> -> 前端轮询</p>
 *
 * <p>职责：
 * <ul>
 *   <li>向前端返回生成任务状态、阶段、planId、错误信息和结果摘要。</li>
 *   <li>把任务状态和阶段派生成中文展示字段，让前端少维护一套硬编码映射。</li>
 *   <li>隐藏 Store 内部实现差异，让内存和 JDBC 模式返回同一份 JSON 结构。</li>
 *   <li>为第八阶段进度可视化和后续任务历史列表提供稳定出参。</li>
 * </ul>
 * </p>
 */
public class GenerationJobResponse {

    /** 生成任务 ID。 */
    private String jobId;

    /** 开发阶段用户 ID。 */
    private String userId;

    /** 会话 ID。 */
    private String sessionId;

    /** 来源需求表 ID。 */
    private String requirementId;

    /** 生成成功后的旅行计划 ID。 */
    private String planId;

    /** 当前任务状态。 */
    private GenerationJobStatus status;

    /** 当前任务阶段。 */
    private GenerationJobStage currentStage;

    /** 创建任务时的请求快照。 */
    private Map<String, Object> request = new LinkedHashMap<>();

    /** 任务结束后的结果摘要。 */
    private Map<String, Object> result = new LinkedHashMap<>();

    /** 失败时的错误摘要。 */
    private String errorMessage;

    /** 最终是否保留本次扣费。 */
    private boolean creditCharged;

    /** 任务创建时间。 */
    private Instant createdAt;

    /** 任务最近更新时间。 */
    private Instant updatedAt;

    /** 任务终态时间。 */
    private Instant finishedAt;

    /** 面向前端展示的简短提示。 */
    private String assistantMessage;

    /** 面向前端展示的任务状态中文短标题。 */
    private String statusLabel;

    /** 面向前端展示的任务阶段中文短标题。 */
    private String stageLabel;

    /** 面向前端展示的当前阶段说明。 */
    private String stageDescription;

    /** 面向前端展示的建议进度百分比，不代表真实模型执行百分比。 */
    private int progressPercent;

    /** 面向前端展示的下一步操作建议。 */
    private String actionHint;

    /** 任务已运行或总耗时秒数。 */
    private long durationSeconds;

    /** 当前任务是否已经进入终态。 */
    private boolean terminal;

    /** 失败后是否建议用户修正需求或稍后重试。 */
    private boolean recoverable;

    /**
     * 从领域模型构造前端查询响应。
     *
     * <p>展示字段全部在这里派生，GenerationJob 仍然只保存核心状态和审计快照。</p>
     *
     * @param job 生成任务领域模型
     * @return 前端任务响应
     */
    public static GenerationJobResponse from(GenerationJob job) {
        // Store 保存的是完整任务模型；响应 DTO 只挑前端轮询和历史列表需要展示的字段。
        GenerationJobResponse response = new GenerationJobResponse();
        response.setJobId(job.getJobId());
        response.setUserId(job.getUserId());
        response.setSessionId(job.getSessionId());
        response.setRequirementId(job.getRequirementId());
        response.setPlanId(job.getPlanId());
        response.setStatus(job.getStatus());
        response.setCurrentStage(job.getCurrentStage());
        response.setRequest(job.getRequest());
        response.setResult(job.getResult());
        response.setErrorMessage(job.getErrorMessage());
        response.setCreditCharged(job.isCreditCharged());
        response.setCreatedAt(job.getCreatedAt());
        response.setUpdatedAt(job.getUpdatedAt());
        response.setFinishedAt(job.getFinishedAt());
        response.setAssistantMessage(buildAssistantMessage(job));
        response.setStatusLabel(statusLabel(job.getStatus()));
        response.setStageLabel(stageLabel(job.getCurrentStage()));
        response.setStageDescription(stageDescription(job.getCurrentStage()));
        response.setProgressPercent(progressPercent(job));
        response.setActionHint(actionHint(job));
        response.setDurationSeconds(durationSeconds(job));
        response.setTerminal(isTerminal(job.getStatus()));
        response.setRecoverable(isRecoverable(job));
        return response;
    }

    private static String buildAssistantMessage(GenerationJob job) {
        // 终态任务给明确结果，运行中任务给“继续等待”的提示，避免前端自己拼文案。
        if (job.getStatus() == GenerationJobStatus.SUCCEEDED) {
            return "旅行方案已经生成完成。";
        }
        if (job.getStatus() == GenerationJobStatus.FAILED) {
            return job.getErrorMessage() == null || job.getErrorMessage().isBlank()
                    ? "旅行方案生成失败。"
                    : job.getErrorMessage();
        }
        if (job.getStatus() == GenerationJobStatus.CANCELLED) {
            return "旅行方案生成任务已取消。";
        }
        return "旅行方案生成任务正在执行，请稍等。";
    }

    private static String statusLabel(GenerationJobStatus status) {
        if (status == null) {
            return "等待中";
        }
        return switch (status) {
            case PENDING -> "等待生成";
            case RUNNING -> "生成中";
            case SUCCEEDED -> "已完成";
            case FAILED -> "生成失败";
            case CANCELLED -> "已取消";
        };
    }

    private static String stageLabel(GenerationJobStage stage) {
        if (stage == null) {
            return "准备任务";
        }
        return switch (stage) {
            case CREATED -> "创建任务";
            case VALIDATING_REQUIREMENT -> "检查需求";
            case CHARGING_CREDIT -> "扣除额度";
            case RUNNING_GRAPH -> "执行核心规划";
            case SAVING_PLAN -> "保存方案";
            case REFUNDING_CREDIT -> "退回额度";
            case FINISHED -> "任务结束";
        };
    }

    private static String stageDescription(GenerationJobStage stage) {
        if (stage == null) {
            return "任务已经创建，正在准备进入生成流程。";
        }
        return switch (stage) {
            case CREATED -> "任务记录已经创建，后台线程即将开始执行。";
            case VALIDATING_REQUIREMENT -> "正在检查需求表是否已经确认，以及关键字段是否满足完整生成条件。";
            case CHARGING_CREDIT -> "正在扣除一次完整生成额度，避免生成完成后再出现额度不一致。";
            case RUNNING_GRAPH -> "正在调用核心 Agent 工作流，完成知识检索、分支工具调用、方案草拟和风险检查。";
            case SAVING_PLAN -> "核心工作流已经返回结果，正在把最终方案保存为可查看、可修改的计划记录。";
            case REFUNDING_CREDIT -> "生成流程失败且此前已经扣费，系统正在退回本次生成额度。";
            case FINISHED -> "任务已经进入终态，可以查看成功方案或失败原因。";
        };
    }

    private static int progressPercent(GenerationJob job) {
        GenerationJobStatus status = job.getStatus();
        if (status == GenerationJobStatus.SUCCEEDED
                || status == GenerationJobStatus.FAILED
                || status == GenerationJobStatus.CANCELLED) {
            return 100;
        }
        GenerationJobStage stage = job.getCurrentStage();
        if (stage == null) {
            return 0;
        }
        return switch (stage) {
            case CREATED -> 8;
            case VALIDATING_REQUIREMENT -> 22;
            case CHARGING_CREDIT -> 36;
            case RUNNING_GRAPH -> 68;
            case SAVING_PLAN -> 88;
            case REFUNDING_CREDIT -> 92;
            case FINISHED -> 100;
        };
    }

    private static String actionHint(GenerationJob job) {
        GenerationJobStatus status = job.getStatus();
        if (status == GenerationJobStatus.SUCCEEDED) {
            return "旅行方案已经生成完成，页面会自动加载结果。";
        }
        if (status == GenerationJobStatus.FAILED) {
            return isRecoverable(job)
                    ? "可以回到需求表检查信息后重新生成，也可以稍后重试。"
                    : "请查看失败原因，必要时联系开发者排查。";
        }
        if (status == GenerationJobStatus.CANCELLED) {
            return "任务已经取消，可以重新确认需求后再生成。";
        }
        return "请稍等，生成完成后会自动展示方案；刷新页面后也可以从最近任务中恢复。";
    }

    private static long durationSeconds(GenerationJob job) {
        Instant start = job.getCreatedAt();
        Instant end = job.getFinishedAt() == null ? job.getUpdatedAt() : job.getFinishedAt();
        if (start == null || end == null || end.isBefore(start)) {
            return 0;
        }
        return Math.max(0, Duration.between(start, end).toSeconds());
    }

    private static boolean isTerminal(GenerationJobStatus status) {
        return status == GenerationJobStatus.SUCCEEDED
                || status == GenerationJobStatus.FAILED
                || status == GenerationJobStatus.CANCELLED;
    }

    private static boolean isRecoverable(GenerationJob job) {
        if (job.getStatus() != GenerationJobStatus.FAILED) {
            return false;
        }
        String message = job.getErrorMessage() == null ? "" : job.getErrorMessage();
        // 需求、额度、外部工具和模型异常都属于用户或系统可以处理后重试的失败。
        return message.isBlank()
                || message.contains("需求")
                || message.contains("额度")
                || message.contains("工具")
                || message.contains("失败")
                || message.contains("异常")
                || message.contains("timeout")
                || message.contains("Timeout");
    }

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
        this.status = status;
    }

    public GenerationJobStage getCurrentStage() {
        return currentStage;
    }

    public void setCurrentStage(GenerationJobStage currentStage) {
        this.currentStage = currentStage;
    }

    public Map<String, Object> getRequest() {
        return request;
    }

    public void setRequest(Map<String, Object> request) {
        this.request = request == null ? new LinkedHashMap<>() : new LinkedHashMap<>(request);
    }

    public Map<String, Object> getResult() {
        return result;
    }

    public void setResult(Map<String, Object> result) {
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
        this.createdAt = createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(Instant finishedAt) {
        this.finishedAt = finishedAt;
    }

    public String getAssistantMessage() {
        return assistantMessage;
    }

    public void setAssistantMessage(String assistantMessage) {
        this.assistantMessage = assistantMessage;
    }

    public String getStatusLabel() {
        return statusLabel;
    }

    public void setStatusLabel(String statusLabel) {
        this.statusLabel = statusLabel;
    }

    public String getStageLabel() {
        return stageLabel;
    }

    public void setStageLabel(String stageLabel) {
        this.stageLabel = stageLabel;
    }

    public String getStageDescription() {
        return stageDescription;
    }

    public void setStageDescription(String stageDescription) {
        this.stageDescription = stageDescription;
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(int progressPercent) {
        this.progressPercent = Math.max(0, Math.min(progressPercent, 100));
    }

    public String getActionHint() {
        return actionHint;
    }

    public void setActionHint(String actionHint) {
        this.actionHint = actionHint;
    }

    public long getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(long durationSeconds) {
        this.durationSeconds = Math.max(0, durationSeconds);
    }

    public boolean isTerminal() {
        return terminal;
    }

    public void setTerminal(boolean terminal) {
        this.terminal = terminal;
    }

    public boolean isRecoverable() {
        return recoverable;
    }

    public void setRecoverable(boolean recoverable) {
        this.recoverable = recoverable;
    }
}
