package com.travel.agent.ai.graph.model;

/**
 * 分支 Agent / 工具执行后的结构化结果。
 *
 * <p>系统架构位置：BranchExecuteNode -> <b>BranchResult</b> -> PlanDraftNode</p>
 *
 * <p>职责：
 * <ul>
 *   <li>保存分支执行是否成功、摘要、原始数据和错误信息。</li>
 *   <li>让 Planner 可以显式区分“已确认数据”和“工具失败/暂未接入”。</li>
 *   <li>避免分支异常直接打断 Graph 主流程。</li>
 * </ul>
 * </p>
 */
public class BranchResult {

    /** 对应 BranchTask 的任务 ID。 */
    private String taskId;

    /** 分支任务类型。 */
    private BranchTaskType type;

    /** 分支任务是否成功得到可用结果。 */
    private boolean success;

    /** 给 Planner 注入 Prompt 的短摘要。 */
    private String summary;

    /** 调试或后续结构化处理使用的原始数据文本。 */
    private String rawData;

    /** 分支失败或降级时的内部错误摘要。 */
    private String errorMessage;

    /**
     * Jackson / 测试场景需要的无参构造器。
     */
    public BranchResult() {
    }

    public BranchResult(String taskId,
                        BranchTaskType type,
                        boolean success,
                        String summary,
                        String rawData,
                        String errorMessage) {
        this.taskId = taskId;
        this.type = type;
        this.success = success;
        this.summary = summary;
        this.rawData = rawData;
        this.errorMessage = errorMessage;
    }

    public static BranchResult success(BranchTask task, String summary, String rawData) {
        // 成功结果保留原始任务 ID 和类型，Planner 可以知道这段信息来自哪个分支。
        return new BranchResult(
                task == null ? null : task.getTaskId(),
                task == null ? null : task.getType(),
                true,
                summary,
                rawData,
                null);
    }

    public static BranchResult failure(BranchTask task, String summary, String errorMessage) {
        // 分支失败不抛出到主流程，而是变成可读摘要，让 Planner 以降级信息继续工作。
        return new BranchResult(
                task == null ? null : task.getTaskId(),
                task == null ? null : task.getType(),
                false,
                summary,
                null,
                errorMessage);
    }

    public String getTaskId() {
        return taskId;
    }

    public void setTaskId(String taskId) {
        this.taskId = taskId;
    }

    public BranchTaskType getType() {
        return type;
    }

    public void setType(BranchTaskType type) {
        this.type = type;
    }

    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getSummary() {
        return summary;
    }

    public void setSummary(String summary) {
        this.summary = summary;
    }

    public String getRawData() {
        return rawData;
    }

    public void setRawData(String rawData) {
        this.rawData = rawData;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }
}
