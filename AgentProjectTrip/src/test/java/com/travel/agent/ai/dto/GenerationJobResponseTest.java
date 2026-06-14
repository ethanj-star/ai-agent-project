package com.travel.agent.ai.dto;

import com.travel.agent.ai.graph.model.GenerationJob;
import com.travel.agent.ai.graph.model.GenerationJobStage;
import com.travel.agent.ai.graph.model.GenerationJobStatus;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * GenerationJobResponse 及列表响应的单元测试。
 *
 * <p>验证第十一阶段新增的前端展示字段不会破坏原有任务状态，并能为刷新恢复提供摘要信息。</p>
 */
class GenerationJobResponseTest {

    /**
     * 运行中任务应派生出中文阶段、进度和耗时。
     */
    @Test
    void fromAddsPresentationFieldsForRunningJob() {
        GenerationJob job = job("job-1", GenerationJobStatus.RUNNING, GenerationJobStage.RUNNING_GRAPH);
        job.setCreatedAt(Instant.parse("2026-06-14T10:00:00Z"));
        job.setUpdatedAt(Instant.parse("2026-06-14T10:00:42Z"));

        GenerationJobResponse response = GenerationJobResponse.from(job);

        assertThat(response.getStatusLabel()).isEqualTo("生成中");
        assertThat(response.getStageLabel()).isEqualTo("执行核心规划");
        assertThat(response.getProgressPercent()).isEqualTo(68);
        assertThat(response.getDurationSeconds()).isEqualTo(42);
        assertThat(response.isTerminal()).isFalse();
        assertThat(response.isRecoverable()).isFalse();
    }

    /**
     * 最近任务列表应返回运行中摘要和最新可恢复 planId。
     */
    @Test
    void listResponseSummarizesRecentJobs() {
        GenerationJob running = job("job-running", GenerationJobStatus.RUNNING, GenerationJobStage.RUNNING_GRAPH);
        GenerationJob succeeded = job("job-done", GenerationJobStatus.SUCCEEDED, GenerationJobStage.FINISHED);
        succeeded.setPlanId("plan-1");

        GenerationJobListResponse response = GenerationJobListResponse.of("s1", List.of(
                GenerationJobResponse.from(running),
                GenerationJobResponse.from(succeeded)
        ));

        assertThat(response.getSessionId()).isEqualTo("s1");
        assertThat(response.getCount()).isEqualTo(2);
        assertThat(response.isHasActiveJob()).isTrue();
        assertThat(response.getLatestJobId()).isEqualTo("job-running");
        assertThat(response.getLatestPlanId()).isEqualTo("plan-1");
    }

    private static GenerationJob job(String jobId,
                                     GenerationJobStatus status,
                                     GenerationJobStage stage) {
        GenerationJob job = new GenerationJob();
        job.setJobId(jobId);
        job.setSessionId("s1");
        job.setRequirementId("req-1");
        job.setStatus(status);
        job.setCurrentStage(stage);
        return job;
    }
}
