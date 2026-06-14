package com.travel.agent.web;

import com.travel.agent.ai.dto.GenerationJobListResponse;
import com.travel.agent.ai.dto.GenerationJobResponse;
import com.travel.agent.ai.graph.store.GenerationJobStore;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * 异步生成任务 HTTP 查询入口。
 *
 * <p>系统架构位置：前端轮询 -> <b>GenerationJobController</b> -> GenerationJobStore</p>
 *
 * <p>职责：
 * <ul>
 *   <li>按 jobId 返回第八阶段生成任务的状态、阶段、错误和 planId。</li>
 *   <li>支持按 sessionId 查询最近任务，让页面刷新后仍能找回生成进度。</li>
 *   <li>只负责查询任务，不直接触发高成本 Graph 生成，也不扣除额度。</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/v1/jobs")
public class GenerationJobController {

    /** 异步生成任务仓库。 */
    private final GenerationJobStore generationJobStore;

    /**
     * 构造任务查询入口。
     *
     * @param generationJobStore 异步生成任务仓库
     */
    public GenerationJobController(GenerationJobStore generationJobStore) {
        this.generationJobStore = generationJobStore;
    }

    /**
     * 按 jobId 查询任务详情。
     *
     * <p>HTTP 语义：只读轮询接口，不触发 Graph，不扣费。</p>
     *
     * @param jobId 任务 ID
     * @return 任务状态响应；不存在时返回 404
     */
    @GetMapping("/{jobId}")
    public ResponseEntity<GenerationJobResponse> getJob(@PathVariable String jobId) {
        // Optional 为空表示 jobId 不存在，直接映射成 HTTP 404。
        return generationJobStore.findById(jobId)
                .map(GenerationJobResponse::from)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 查询某个会话最近的生成任务。
     *
     * <p>HTTP 语义：只读恢复接口，供前端刷新页面后找回最近任务；不触发 Graph，不扣费。</p>
     *
     * @param sessionId 会话 ID
     * @param limit     最大返回数量，默认 5
     * @return 最近任务列表和恢复摘要
     */
    @GetMapping
    public ResponseEntity<GenerationJobListResponse> listJobs(@RequestParam String sessionId,
                                                              @RequestParam(defaultValue = "5") int limit) {
        // limit 做上下界保护，避免前端误传过大数字导致一次查询返回太多历史任务。
        int safeLimit = Math.max(1, Math.min(limit, 20));
        // Store 返回领域模型，Controller 统一转换成前端更稳定的响应 DTO。
        List<GenerationJobResponse> jobs = generationJobStore.findRecentBySessionId(sessionId, safeLimit)
                .stream()
                .map(GenerationJobResponse::from)
                .toList();
        return ResponseEntity.ok(GenerationJobListResponse.of(sessionId, jobs));
    }
}
