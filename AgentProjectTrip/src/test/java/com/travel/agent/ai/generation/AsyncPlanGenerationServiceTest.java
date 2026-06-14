package com.travel.agent.ai.generation;

import com.travel.agent.ai.graph.model.GenerationJob;
import com.travel.agent.ai.graph.model.GenerationJobStage;
import com.travel.agent.ai.graph.model.GenerationJobStatus;
import com.travel.agent.ai.graph.model.GraphResult;
import com.travel.agent.ai.graph.model.RequirementStatus;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import com.travel.agent.ai.graph.store.InMemoryGenerationJobStore;
import com.travel.agent.ai.graph.store.InMemoryRequirementStore;
import com.travel.agent.core.service.UserContextResolver;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.TaskExecutor;

import java.util.function.Consumer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * AsyncPlanGenerationService 的单元测试。
 *
 * <p>验证第八阶段异步任务包装逻辑：创建 job、后台成功落库、运行中任务去重。</p>
 */
class AsyncPlanGenerationServiceTest {

    /**
     * 使用同步执行器时，创建任务后应立即执行生成并把任务标记为 SUCCEEDED。
     */
    @Test
    void createJobRunsGenerationAndMarksSucceeded() {
        InMemoryRequirementStore requirementStore = new InMemoryRequirementStore();
        InMemoryGenerationJobStore jobStore = new InMemoryGenerationJobStore();
        TravelRequirementSpec spec = confirmedSpec("req-1", "s1");
        requirementStore.save(spec);

        PlanGenerationService generationService = mock(PlanGenerationService.class);
        when(generationService.generate(any(), any())).thenAnswer(invocation -> {
            Consumer<GenerationJobStage> stageConsumer = invocation.getArgument(1);
            stageConsumer.accept(GenerationJobStage.RUNNING_GRAPH);
            return successOutcome();
        });

        AsyncPlanGenerationService service = new AsyncPlanGenerationService(
                requirementStore,
                jobStore,
                generationService,
                new UserContextResolver(),
                Runnable::run);

        GenerationJobCreateResult result = service.createJob("req-1");
        GenerationJob saved = jobStore.findById(result.job().getJobId()).orElseThrow();

        assertThat(result.existing()).isFalse();
        assertThat(saved.getStatus()).isEqualTo(GenerationJobStatus.SUCCEEDED);
        assertThat(saved.getPlanId()).isEqualTo("plan-1");
        assertThat(saved.isCreditCharged()).isTrue();
    }

    /**
     * 当同一 requirementId 已有 PENDING 任务时，重复创建应返回已有 job。
     */
    @Test
    void createJobReturnsExistingRunningJob() {
        InMemoryRequirementStore requirementStore = new InMemoryRequirementStore();
        InMemoryGenerationJobStore jobStore = new InMemoryGenerationJobStore();
        requirementStore.save(confirmedSpec("req-1", "s1"));
        TaskExecutor pausedExecutor = ignored -> {
        };

        AsyncPlanGenerationService service = new AsyncPlanGenerationService(
                requirementStore,
                jobStore,
                mock(PlanGenerationService.class),
                new UserContextResolver(),
                pausedExecutor);

        GenerationJobCreateResult first = service.createJob("req-1");
        GenerationJobCreateResult second = service.createJob("req-1");

        assertThat(first.existing()).isFalse();
        assertThat(second.existing()).isTrue();
        assertThat(second.job().getJobId()).isEqualTo(first.job().getJobId());
        assertThat(jobStore.findById(first.job().getJobId()).orElseThrow().getStatus())
                .isEqualTo(GenerationJobStatus.PENDING);
    }

    private static TravelRequirementSpec confirmedSpec(String requirementId, String sessionId) {
        TravelRequirementSpec spec = new TravelRequirementSpec();
        spec.setRequirementId(requirementId);
        spec.setSessionId(sessionId);
        spec.setOriginalMessage("国庆去法国和意大利玩10天，预算1200欧");
        spec.setStatus(RequirementStatus.CONFIRMED);
        return spec;
    }

    private static PlanGenerationOutcome successOutcome() {
        PlanGenerationOutcome outcome = new PlanGenerationOutcome();
        outcome.setRequirementId("req-1");
        outcome.setPlanId("plan-1");
        outcome.setStatus(RequirementStatus.GENERATED);
        outcome.setRemainingCredits(2);
        outcome.setGraphResult(GraphResult.success("answer", java.util.List.of()));
        outcome.setCreditCharged(true);
        return outcome;
    }
}
