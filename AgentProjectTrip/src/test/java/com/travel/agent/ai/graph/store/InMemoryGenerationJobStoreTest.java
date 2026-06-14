package com.travel.agent.ai.graph.store;

import com.travel.agent.ai.graph.model.GenerationJob;
import com.travel.agent.ai.graph.model.GenerationJobStatus;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * InMemoryGenerationJobStore 的单元测试。
 *
 * <p>验证第八阶段异步生成任务的保存、查询、重复点击保护和最近任务列表能力。</p>
 */
class InMemoryGenerationJobStoreTest {

    /**
     * 仓库应按 jobId 保存和读取生成任务。
     */
    @Test
    void saveAndFindById() {
        InMemoryGenerationJobStore store = new InMemoryGenerationJobStore();
        GenerationJob job = job("job-1", "req-1", "s1", GenerationJobStatus.PENDING);

        store.save(job);

        assertThat(store.findById("job-1")).containsSame(job);
    }

    /**
     * 空 jobId 不应写入仓库。
     */
    @Test
    void saveRejectsBlankJobId() {
        InMemoryGenerationJobStore store = new InMemoryGenerationJobStore();

        assertThatThrownBy(() -> store.save(new GenerationJob()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jobId");
    }

    /**
     * findRunningByRequirementId 只应返回 PENDING/RUNNING 任务。
     */
    @Test
    void findRunningByRequirementIdIgnoresFinishedJobs() {
        InMemoryGenerationJobStore store = new InMemoryGenerationJobStore();
        store.save(job("job-done", "req-1", "s1", GenerationJobStatus.SUCCEEDED));
        GenerationJob running = job("job-running", "req-1", "s1", GenerationJobStatus.RUNNING);
        store.save(running);

        assertThat(store.findRunningByRequirementId("req-1")).containsSame(running);
    }

    /**
     * 最近任务列表应按创建时间倒序返回。
     */
    @Test
    void findRecentBySessionIdReturnsNewestFirst() {
        InMemoryGenerationJobStore store = new InMemoryGenerationJobStore();
        GenerationJob older = job("job-older", "req-1", "s1", GenerationJobStatus.SUCCEEDED);
        GenerationJob newer = job("job-newer", "req-2", "s1", GenerationJobStatus.PENDING);
        newer.setCreatedAt(older.getCreatedAt().plusSeconds(5));
        store.save(older);
        store.save(newer);

        assertThat(store.findRecentBySessionId("s1", 2))
                .extracting(GenerationJob::getJobId)
                .containsExactly("job-newer", "job-older");
    }

    private static GenerationJob job(String jobId,
                                     String requirementId,
                                     String sessionId,
                                     GenerationJobStatus status) {
        GenerationJob job = new GenerationJob();
        job.setJobId(jobId);
        job.setRequirementId(requirementId);
        job.setSessionId(sessionId);
        job.setUserId(sessionId);
        job.setStatus(status);
        return job;
    }
}
