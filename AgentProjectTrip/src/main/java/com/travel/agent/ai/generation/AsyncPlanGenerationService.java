package com.travel.agent.ai.generation;

import com.travel.agent.ai.graph.model.GenerationJob;
import com.travel.agent.ai.graph.model.GenerationJobStage;
import com.travel.agent.ai.graph.model.GenerationJobStatus;
import com.travel.agent.ai.graph.model.GraphResult;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import com.travel.agent.ai.graph.store.GenerationJobStore;
import com.travel.agent.ai.graph.store.RequirementStore;
import com.travel.agent.core.service.UserContextResolver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.core.task.TaskExecutor;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * 异步完整旅行规划生成服务。
 *
 * <p>系统架构位置：RequirementController -> <b>AsyncPlanGenerationService</b> -> PlanGenerationService / GenerationJobStore</p>
 *
 * <p>职责：
 * <ul>
 *   <li>把一次完整规划生成包装成 GenerationJob，并立即把 jobId 返回给前端。</li>
 *   <li>后台执行真实生成流程，持续更新任务状态和阶段，供前端轮询展示。</li>
 *   <li>防止同一个 requirementId 在已有任务运行时重复创建任务，从工程上降低重复扣费风险。</li>
 * </ul>
 * </p>
 */
@Service
public class AsyncPlanGenerationService {

    private static final Logger log = LoggerFactory.getLogger(AsyncPlanGenerationService.class);

    /** 需求表仓库，用于读取任务对应的 TravelRequirementSpec。 */
    private final RequirementStore requirementStore;

    /** 异步任务仓库，用于保存任务状态和查询运行中任务。 */
    private final GenerationJobStore generationJobStore;

    /** 真实完整规划生成服务，异步任务只负责包装和状态追踪。 */
    private final PlanGenerationService planGenerationService;

    /** 开发阶段 userId/sessionId 解析工具。 */
    private final UserContextResolver userContextResolver;

    /** 第八阶段专用后台线程池。 */
    private final TaskExecutor generationTaskExecutor;

    /**
     * 构造异步生成服务。
     *
     * @param requirementStore       需求表仓库
     * @param generationJobStore     生成任务仓库
     * @param planGenerationService  完整规划生成服务
     * @param userContextResolver    用户上下文解析器
     * @param generationTaskExecutor 第八阶段任务线程池
     */
    public AsyncPlanGenerationService(RequirementStore requirementStore,
                                      GenerationJobStore generationJobStore,
                                      PlanGenerationService planGenerationService,
                                      UserContextResolver userContextResolver,
                                      @Qualifier("generationTaskExecutor")
                                      TaskExecutor generationTaskExecutor) {
        this.requirementStore = requirementStore;
        this.generationJobStore = generationJobStore;
        this.planGenerationService = planGenerationService;
        this.userContextResolver = userContextResolver;
        this.generationTaskExecutor = generationTaskExecutor;
    }

    /**
     * 为指定需求表创建异步生成任务。
     *
     * <p>如果同一个 requirementId 已有 PENDING/RUNNING 任务，本方法直接返回已有任务，
     * 不再创建新任务，也不会触发第二次扣费。</p>
     *
     * @param requirementId 需求表 ID
     * @return 新建或已存在的生成任务创建结果
     */
    public GenerationJobCreateResult createJob(String requirementId) {
        if (!hasText(requirementId)) {
            throw new IllegalArgumentException("requirementId must not be blank");
        }
        // 先确认需求表存在。找不到时抛 NoSuchElementException，由 Controller 转成 404。
        TravelRequirementSpec spec = requirementStore.findById(requirementId)
                .orElseThrow(() -> new NoSuchElementException("Requirement not found: " + requirementId));

        // 幂等保护：同一需求表已有运行中任务时返回旧 jobId，不重复创建后台任务。
        return generationJobStore.findRunningByRequirementId(requirementId)
                .map(job -> new GenerationJobCreateResult(job, true))
                .orElseGet(() -> new GenerationJobCreateResult(createAndSubmit(spec), false));
    }

    private GenerationJob createAndSubmit(TravelRequirementSpec spec) {
        // Step 1：创建任务快照。此时还没有真正跑 Graph，所以状态是 PENDING / CREATED。
        GenerationJob job = new GenerationJob();
        job.setJobId("job-" + UUID.randomUUID());
        job.setRequirementId(spec.getRequirementId());
        job.setSessionId(userContextResolver.resolveSessionId(spec.getSessionId(), null));
        job.setUserId(userContextResolver.resolveUserId(null, spec.getSessionId()));
        job.setStatus(GenerationJobStatus.PENDING);
        job.setCurrentStage(GenerationJobStage.CREATED);
        job.setRequest(buildRequestSnapshot(spec));
        generationJobStore.save(job);

        log.info("[GenerationJob] created, jobId={}, requirementId={}",
                job.getJobId(), job.getRequirementId());

        // Step 2：提交后台线程。HTTP 接口会立刻返回 jobId，真实生成在 runJob() 中继续。
        generationTaskExecutor.execute(() -> runJob(job.getJobId()));
        return job;
    }

    private void runJob(String jobId) {
        // 后台线程启动后重新从仓库读取任务，避免使用提交时可能已经过期的内存对象。
        GenerationJob job = generationJobStore.findById(jobId).orElse(null);
        if (job == null) {
            log.warn("[GenerationJob] skipped missing job, jobId={}", jobId);
            return;
        }

        try {
            // 重新读取需求表，确保后台生成使用的是仓库中的最新确认状态。
            TravelRequirementSpec spec = requirementStore.findById(job.getRequirementId())
                    .orElseThrow(() -> new NoSuchElementException("Requirement not found: " + job.getRequirementId()));
            // PlanGenerationService 负责真正扣费、调用 Graph、保存最终 plan；这里通过回调同步任务阶段。
            PlanGenerationOutcome outcome = planGenerationService.generate(spec,
                    stage -> updateStage(jobId, stage));
            finishJob(jobId, outcome);
        } catch (Exception e) {
            // 兜底异常不能丢任务。任务标记 FAILED 后，前端轮询才能看到失败原因并停止等待。
            log.error("[GenerationJob] failed unexpectedly, jobId={}, error={}", jobId, e.getMessage());
            GenerationJob latest = generationJobStore.findById(jobId).orElse(job);
            latest.markFailed(defaultText(e.getMessage(), "异步生成任务执行失败。"),
                    Map.of("exception", e.getClass().getSimpleName()));
            latest.setCreditCharged(false);
            generationJobStore.save(latest);
        }
    }

    private void updateStage(String jobId, GenerationJobStage stage) {
        generationJobStore.findById(jobId).ifPresent(job -> {
            // 阶段更新是给前端进度条看的，因此每次回调都持久化当前阶段。
            job.markRunning(stage);
            generationJobStore.save(job);
            log.info("[GenerationJob] stage updated, jobId={}, stage={}", jobId, stage);
        });
    }

    private void finishJob(String jobId, PlanGenerationOutcome outcome) {
        // 结束时再次读取最新任务，避免覆盖执行期间写入的阶段和时间戳。
        GenerationJob job = generationJobStore.findById(jobId)
                .orElseThrow(() -> new NoSuchElementException("Generation job not found: " + jobId));
        job.setCreditCharged(outcome.isCreditCharged());
        Map<String, Object> result = buildResultSnapshot(outcome);
        if (outcome.isSucceeded()) {
            // 成功任务必须写入 planId，前端拿它跳转或读取完整方案。
            job.markSucceeded(outcome.getPlanId(), result);
            log.info("[GenerationJob] succeeded, jobId={}, requirementId={}, planId={}",
                    jobId, job.getRequirementId(), outcome.getPlanId());
        } else {
            // 生成失败也要保存 GraphResult 摘要，方便前端展示可读失败原因。
            GraphResult graphResult = outcome.getGraphResult();
            String errorMessage = graphResult == null
                    ? "完整规划生成失败。"
                    : defaultText(graphResult.getErrorMessage(), graphResult.getAnswer());
            job.markFailed(errorMessage, result);
            log.info("[GenerationJob] finished as failed, jobId={}, requirementId={}, error={}",
                    jobId, job.getRequirementId(), errorMessage);
        }
        generationJobStore.save(job);
    }

    private static Map<String, Object> buildRequestSnapshot(TravelRequirementSpec spec) {
        Map<String, Object> request = new LinkedHashMap<>();
        // request 快照用于任务详情页和排查问题；只放关键字段，避免把整张需求表无限膨胀地塞进任务表。
        request.put("requirementId", spec.getRequirementId());
        request.put("sessionId", spec.getSessionId());
        request.put("originalMessage", spec.getOriginalMessage());
        request.put("destinations", spec.getDestinations());
        request.put("startDateText", spec.getStartDateText());
        request.put("durationDays", spec.getDurationDays());
        request.put("budgetAmount", spec.getBudgetAmount());
        request.put("budgetCurrency", spec.getBudgetCurrency());
        return request;
    }

    private static Map<String, Object> buildResultSnapshot(PlanGenerationOutcome outcome) {
        Map<String, Object> result = new LinkedHashMap<>();
        // result 快照记录最终生成结果和额度变化；完整计划正文仍以 planId 读取 TravelPlanRecord。
        result.put("requirementId", outcome.getRequirementId());
        result.put("planId", outcome.getPlanId());
        result.put("requirementStatus", outcome.getStatus());
        result.put("remainingCredits", outcome.getRemainingCredits());
        result.put("creditCharged", outcome.isCreditCharged());
        result.put("refunded", outcome.isRefunded());
        result.put("graphResult", outcome.getGraphResult());
        return result;
    }

    private static String defaultText(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
