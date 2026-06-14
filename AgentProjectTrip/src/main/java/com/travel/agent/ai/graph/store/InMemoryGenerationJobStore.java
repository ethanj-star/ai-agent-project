package com.travel.agent.ai.graph.store;

import com.travel.agent.ai.graph.model.GenerationJob;
import com.travel.agent.ai.graph.model.GenerationJobStatus;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 内存版异步生成任务仓库。
 *
 * <p>系统架构位置：AsyncPlanGenerationService -> GenerationJobStore -> <b>InMemoryGenerationJobStore</b></p>
 *
 * <p>职责：
 * <ul>
 *   <li>为 memory 持久化模式提供无需数据库的任务状态保存能力。</li>
 *   <li>支持按 jobId 查询、按 requirementId 防重复生成、按 sessionId 查看最近任务。</li>
 *   <li>让第八阶段异步任务可以在单元测试和轻量开发模式中独立运行。</li>
 * </ul>
 * </p>
 */
@Component
@ConditionalOnProperty(name = "travel.persistence.type", havingValue = "memory", matchIfMissing = true)
public class InMemoryGenerationJobStore implements GenerationJobStore {

    /** jobId -> GenerationJob 的单机内存表。 */
    private final Map<String, GenerationJob> store = new ConcurrentHashMap<>();

    /**
     * 保存或覆盖一条任务记录。
     *
     * @param job 生成任务
     * @return 原样返回保存后的任务
     */
    @Override
    public GenerationJob save(GenerationJob job) {
        if (job == null || job.getJobId() == null || job.getJobId().isBlank()) {
            throw new IllegalArgumentException("jobId must not be blank");
        }
        // 任务阶段会被后台线程反复更新，同一个 jobId 覆盖保存即可。
        store.put(job.getJobId(), job);
        return job;
    }

    /**
     * 按 jobId 查询任务。
     *
     * @param jobId 任务 ID
     * @return 找到时返回任务
     */
    @Override
    public Optional<GenerationJob> findById(String jobId) {
        if (jobId == null || jobId.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(store.get(jobId));
    }

    /**
     * 查询同一需求表的运行中任务。
     *
     * @param requirementId 需求表 ID
     * @return 找到 PENDING 或 RUNNING 任务时返回
     */
    @Override
    public Optional<GenerationJob> findRunningByRequirementId(String requirementId) {
        if (requirementId == null || requirementId.isBlank()) {
            return Optional.empty();
        }
        return store.values().stream()
                .filter(job -> requirementId.equals(job.getRequirementId()))
                .filter(job -> job.getStatus() == GenerationJobStatus.PENDING
                        || job.getStatus() == GenerationJobStatus.RUNNING)
                // 如果异常情况下存在多个运行中任务，返回最早的一个，保持重复点击保护稳定。
                .min(Comparator.comparing(GenerationJob::getCreatedAt));
    }

    /**
     * 查询某个会话最近的任务。
     *
     * @param sessionId 会话 ID
     * @param limit     最大返回数量
     * @return 最近任务列表
     */
    @Override
    public List<GenerationJob> findRecentBySessionId(String sessionId, int limit) {
        if (sessionId == null || sessionId.isBlank() || limit <= 0) {
            return List.of();
        }
        return store.values().stream()
                .filter(job -> sessionId.equals(job.getSessionId()))
                // 最近任务列表按创建时间倒序，便于前端恢复页面时展示最新进度。
                .sorted(Comparator.comparing(GenerationJob::getCreatedAt).reversed())
                .limit(limit)
                .toList();
    }
}
