package com.travel.agent.ai.dto;

import com.travel.agent.ai.graph.model.GenerationJobStatus;

import java.util.List;

/**
 * 异步生成任务列表查询响应 DTO。
 *
 * <p>系统架构位置：GenerationJobController -> GenerationJobStore -> <b>GenerationJobListResponse</b> -> 前端任务恢复</p>
 *
 * <p>职责：
 * <ul>
 *   <li>包装某个 sessionId 下的最近生成任务列表，避免列表接口直接暴露裸数组。</li>
 *   <li>额外提供最新任务、最新 plan 和是否存在运行中任务等摘要，方便前端刷新页面后恢复状态。</li>
 *   <li>只负责表达查询结果，不执行数据库查询，也不触发 Graph 和扣费。</li>
 * </ul>
 * </p>
 */
public class GenerationJobListResponse {

    /** 查询任务列表时使用的会话 ID。 */
    private String sessionId;

    /** 本次返回的任务数量。 */
    private int count;

    /** 当前列表中是否存在 PENDING 或 RUNNING 任务。 */
    private boolean hasActiveJob;

    /** 当前列表中的最新任务 ID，列表为空时为 null。 */
    private String latestJobId;

    /** 当前列表中最新可用的 planId，通常来自最近的成功任务。 */
    private String latestPlanId;

    /** 最近任务列表。 */
    private List<GenerationJobResponse> jobs = List.of();

    /**
     * 构造最近任务列表响应。
     *
     * @param sessionId 会话 ID
     * @param jobs      已转换好的任务响应列表，通常已按时间倒序排列
     * @return 前端任务列表响应
     */
    public static GenerationJobListResponse of(String sessionId, List<GenerationJobResponse> jobs) {
        List<GenerationJobResponse> safeJobs = jobs == null ? List.of() : List.copyOf(jobs);
        GenerationJobListResponse response = new GenerationJobListResponse();
        response.setSessionId(sessionId);
        response.setJobs(safeJobs);
        response.setCount(safeJobs.size());
        response.setHasActiveJob(safeJobs.stream().anyMatch(GenerationJobListResponse::isActive));
        response.setLatestJobId(safeJobs.isEmpty() ? null : safeJobs.get(0).getJobId());
        response.setLatestPlanId(findLatestPlanId(safeJobs));
        return response;
    }

    private static boolean isActive(GenerationJobResponse job) {
        return job.getStatus() == GenerationJobStatus.PENDING
                || job.getStatus() == GenerationJobStatus.RUNNING;
    }

    private static String findLatestPlanId(List<GenerationJobResponse> jobs) {
        return jobs.stream()
                .filter(job -> job.getPlanId() != null && !job.getPlanId().isBlank())
                .map(GenerationJobResponse::getPlanId)
                .findFirst()
                .orElse(null);
    }

    public String getSessionId() {
        return sessionId;
    }

    public void setSessionId(String sessionId) {
        this.sessionId = sessionId;
    }

    public int getCount() {
        return count;
    }

    public void setCount(int count) {
        this.count = Math.max(0, count);
    }

    public boolean isHasActiveJob() {
        return hasActiveJob;
    }

    public void setHasActiveJob(boolean hasActiveJob) {
        this.hasActiveJob = hasActiveJob;
    }

    public String getLatestJobId() {
        return latestJobId;
    }

    public void setLatestJobId(String latestJobId) {
        this.latestJobId = latestJobId;
    }

    public String getLatestPlanId() {
        return latestPlanId;
    }

    public void setLatestPlanId(String latestPlanId) {
        this.latestPlanId = latestPlanId;
    }

    public List<GenerationJobResponse> getJobs() {
        return jobs;
    }

    public void setJobs(List<GenerationJobResponse> jobs) {
        this.jobs = jobs == null ? List.of() : List.copyOf(jobs);
    }
}
