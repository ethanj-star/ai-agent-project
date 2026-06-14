package com.travel.agent.web;

import com.travel.agent.ai.agents.RequirementExtractionAgent;
import com.travel.agent.ai.dto.GenerationJobCreateResponse;
import com.travel.agent.ai.dto.RequirementConfirmRequest;
import com.travel.agent.ai.dto.RequirementDraftRequest;
import com.travel.agent.ai.dto.RequirementDraftResponse;
import com.travel.agent.ai.dto.RequirementGenerateResponse;
import com.travel.agent.ai.generation.AsyncPlanGenerationService;
import com.travel.agent.ai.generation.GenerationJobCreateResult;
import com.travel.agent.ai.generation.PlanGenerationOutcome;
import com.travel.agent.ai.generation.PlanGenerationService;
import com.travel.agent.ai.graph.model.GraphResult;
import com.travel.agent.ai.graph.model.RequirementStatus;
import com.travel.agent.ai.graph.model.RequirementValidation;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import com.travel.agent.ai.graph.node.RequirementValidationNode;
import com.travel.agent.ai.graph.store.RequirementStore;
import com.travel.agent.ai.memory.UserMemoryService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.NoSuchElementException;
import java.util.UUID;

/**
 * 结构化旅行需求表 HTTP 入口（Web 层 - 第五阶段生成门控）。
 *
 * <p>系统架构位置：<b>Web 层</b> -> RequirementExtractionAgent / RequirementValidationNode -> LangGraphPlannerFacade</p>
 *
 * <p>职责：
 * <ul>
 *   <li>提供自然语言抽取需求表、保存编辑、确认需求表和生成完整规划的 API。</li>
 *   <li>在进入高成本 Agent 工作流前执行字段校验和模拟额度门控。</li>
 *   <li>把已确认的 {@link TravelRequirementSpec} 注入现有 Graph 黑箱，复用前四阶段规划能力。</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/v1/requirements")
public class RequirementController {

    private static final Logger log = LoggerFactory.getLogger(RequirementController.class);

    private final RequirementExtractionAgent extractionAgent;
    private final RequirementValidationNode validationNode;
    private final RequirementStore requirementStore;
    private final PlanGenerationService planGenerationService;
    private final AsyncPlanGenerationService asyncPlanGenerationService;
    private final UserMemoryService userMemoryService;

    /**
     * 构造器注入第五阶段入口需要的服务。
     *
     * @param extractionAgent 自然语言需求抽取 Agent
     * @param validationNode  需求表校验节点
     * @param requirementStore 需求表仓库
     * @param planGenerationService 同步完整规划生成服务
     * @param asyncPlanGenerationService 第八阶段异步生成服务
     * @param userMemoryService 第七阶段用户记忆服务
     */
    public RequirementController(RequirementExtractionAgent extractionAgent,
                                 RequirementValidationNode validationNode,
                                 RequirementStore requirementStore,
                                 PlanGenerationService planGenerationService,
                                 AsyncPlanGenerationService asyncPlanGenerationService,
                                 UserMemoryService userMemoryService) {
        this.extractionAgent = extractionAgent;
        this.validationNode = validationNode;
        this.requirementStore = requirementStore;
        this.planGenerationService = planGenerationService;
        this.asyncPlanGenerationService = asyncPlanGenerationService;
        this.userMemoryService = userMemoryService;
    }

    /**
     * 从用户自然语言中抽取一张结构化旅行需求表。
     *
     * <p>需求整理阶段不扣费，也不会进入 RAG、分支工具或 Planner。
     * 如果模型抽取失败，RequirementExtractionAgent 会降级返回规则抽取结果，前端仍可展示表单。</p>
     *
     * @param request 包含 sessionId 和 message 的抽取请求
     * @return 需求表草稿、校验结果和面向用户的提示语
     */
    @PostMapping("/draft")
    public ResponseEntity<RequirementDraftResponse> draft(@RequestBody RequirementDraftRequest request) {
        // 草稿接口必须有用户原始输入，否则抽取 Agent 没有可分析的文本。
        if (request == null || !hasText(request.getMessage())) {
            return ResponseEntity.badRequest().body(new RequirementDraftResponse(
                    null,
                    null,
                    null,
                    "参数 message 不能为空。"));
        }

        // 先抽取成结构化需求表，再立即校验，让前端能展示“已识别字段 + 还缺什么”。
        TravelRequirementSpec spec = extractionAgent.extract(request.getSessionId(), request.getMessage());
        RequirementValidation validation = validationNode.validate(spec);
        // 草稿也要保存，用户后续编辑、确认和异步生成都依赖同一个 requirementId。
        requirementStore.save(spec);
        log.info("[Requirement] draft created, requirementId={}, status={}",
                spec.getRequirementId(), spec.getStatus());

        return ResponseEntity.ok(buildResponse(spec, validation));
    }

    /**
     * 直接创建一张手动填写的需求草稿。
     *
     * <p>本接口服务第十阶段“直接填表”入口：前端不需要先伪造自然语言 message，
     * 后端也不会调用 LLM、不会扣费、不会进入 Graph。它只负责给需求表补齐
     * requirementId / sessionId / status，执行同一套校验规则并保存草稿。</p>
     *
     * @param incoming 前端表单提交的结构化需求
     * @return 已保存的需求草稿、校验结果和下一步提示
     */
    @PostMapping
    public ResponseEntity<RequirementDraftResponse> createManualDraft(@RequestBody TravelRequirementSpec incoming) {
        if (incoming == null) {
            return ResponseEntity.badRequest().body(new RequirementDraftResponse(
                    null,
                    null,
                    null,
                    "需求表不能为空。"));
        }

        // 手动建表不依赖模型抽取，因此必须在 Web 层补齐最小生命周期字段。
        TravelRequirementSpec spec = prepareManualDraft(incoming);
        RequirementValidation validation = validationNode.validate(spec);
        requirementStore.save(spec);
        log.info("[Requirement] manual draft created, requirementId={}, status={}",
                spec.getRequirementId(), spec.getStatus());

        return ResponseEntity.status(HttpStatus.CREATED).body(buildResponse(spec, validation));
    }

    /**
     * 保存前端编辑后的需求表。
     *
     * <p>前端可以把表单修改后的完整 spec PUT 回来；Controller 会保留旧需求表的
     * requirementId、sessionId 和 originalMessage 等关键上下文，再重新校验状态。</p>
     *
     * @param requirementId 需求表 ID
     * @param incoming      前端提交的最新需求表
     * @return 更新后的需求表和校验结果
     */
    @PutMapping("/{requirementId}")
    public ResponseEntity<RequirementDraftResponse> update(@PathVariable String requirementId,
                                                           @RequestBody TravelRequirementSpec incoming) {
        // requirementId 来自 URL，是这次更新要落到哪张需求表的唯一定位信息。
        if (!hasText(requirementId)) {
            return ResponseEntity.badRequest().body(new RequirementDraftResponse(
                    null,
                    null,
                    null,
                    "requirementId 不能为空。"));
        }
        if (incoming == null) {
            return ResponseEntity.badRequest().body(new RequirementDraftResponse(
                    requirementId,
                    null,
                    null,
                    "需求表不能为空。"));
        }

        // 已存在的需求表要保留 sessionId、originalMessage 等上下文；新 ID 则按传入内容创建。
        TravelRequirementSpec merged = requirementStore.findById(requirementId)
                .map(existing -> mergeForUpdate(requirementId, existing, incoming))
                .orElseGet(() -> {
                    incoming.setRequirementId(requirementId);
                    return incoming;
                });
        // 每次保存前都重新校验，因为用户可能刚刚补齐或删掉了必填字段。
        RequirementValidation validation = validationNode.validate(merged);
        requirementStore.save(merged);
        return ResponseEntity.ok(buildResponse(merged, validation));
    }

    /**
     * 确认需求表。
     *
     * <p>只有校验通过的需求表才能进入 CONFIRMED 状态。确认接口不扣费；
     * 真正扣费发生在 generate 接口，方便用户确认后仍能取消。</p>
     *
     * @param requirementId 需求表 ID
     * @param request       可选确认请求体，可携带最后一次编辑后的 spec
     * @return 确认后的需求表；字段不完整时返回 400 和缺失项
     */
    @PostMapping("/{requirementId}/confirm")
    public ResponseEntity<RequirementDraftResponse> confirm(@PathVariable String requirementId,
                                                            @RequestBody(required = false)
                                                            RequirementConfirmRequest request) {
        // 先查仓库，避免用户直接确认一个不存在或已过期的 requirementId。
        return requirementStore.findById(requirementId)
                .map(existing -> {
                    // 如果前端在确认时顺手提交了最后一次编辑内容，先合并再校验。
                    TravelRequirementSpec spec = request != null && request.getSpec() != null
                            ? mergeForUpdate(requirementId, existing, request.getSpec())
                            : existing;
                    RequirementValidation validation = validationNode.validate(spec);
                    if (!validation.isReadyToConfirm()) {
                        // 校验未通过时仍保存草稿，方便前端展示最新修改后的缺失项。
                        requirementStore.save(spec);
                        return ResponseEntity.badRequest().body(buildResponse(spec, validation));
                    }
                    // 只有完整需求才进入 CONFIRMED，后续 generate 会以这个状态作为门控。
                    spec.setStatus(RequirementStatus.CONFIRMED);
                    requirementStore.save(spec);
                    // 确认后的长期偏好同步进记忆系统，下一次规划可自动带上用户偏好。
                    userMemoryService.syncFromConfirmedRequirement(spec);
                    return ResponseEntity.ok(buildResponse(spec, validation));
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(new RequirementDraftResponse(
                        requirementId,
                        null,
                        null,
                        "没有找到对应的需求表。")));
    }

    /**
     * 基于已确认需求表生成完整旅行规划。
     *
     * <p>这是第八阶段保留的同步调试入口。真实前端优先调用 generate-async，
     * 但同步接口仍复用 {@link PlanGenerationService}，方便开发时直接观察完整返回体。</p>
     *
     * @param requirementId 需求表 ID
     * @return 完整规划结果和剩余额度
     */
    @PostMapping("/{requirementId}/generate")
    public ResponseEntity<RequirementGenerateResponse> generate(@PathVariable String requirementId) {
        // 同步生成用于调试：查不到需求表时直接 404，不进入扣费或 Graph 流程。
        return requirementStore.findById(requirementId)
                .map(spec -> {
                    // PlanGenerationService 内部负责状态检查、扣费、Graph 调用和失败回滚。
                    PlanGenerationOutcome outcome = planGenerationService.generate(spec);
                    return ResponseEntity.status(outcome.getHttpStatusCode()).body(outcome.toResponse());
                })
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        new RequirementGenerateResponse(
                                requirementId,
                                null,
                                0,
                                GraphResult.failure("没有找到对应的需求表。", "Requirement not found"))));
    }

    /**
     * 基于已确认需求表创建异步完整规划生成任务。
     *
     * <p>本接口只负责快速创建 GenerationJob 并返回 jobId，不等待 LangGraph 完成。
     * 前端随后通过 /api/v1/jobs/{jobId} 轮询任务状态，成功后再按 planId 读取完整方案。</p>
     *
     * @param requirementId 需求表 ID
     * @return 生成任务创建结果
     */
    @PostMapping("/{requirementId}/generate-async")
    public ResponseEntity<GenerationJobCreateResponse> generateAsync(@PathVariable String requirementId) {
        try {
            // createJob 会处理“同一需求已有运行中任务”的情况，避免重复扣费和重复生成。
            GenerationJobCreateResult result = asyncPlanGenerationService.createJob(requirementId);
            // 已有任务返回 200，新建任务返回 202，前端据此决定是恢复轮询还是展示新任务。
            return ResponseEntity.status(result.existing() ? HttpStatus.OK : HttpStatus.ACCEPTED)
                    .body(GenerationJobCreateResponse.from(result.job(), result.existing()));
        } catch (NoSuchElementException e) {
            // Service 用 NoSuchElementException 表示 requirementId 不存在，Web 层翻译成 404 响应。
            return ResponseEntity.status(HttpStatus.NOT_FOUND).body(notFoundJobResponse(requirementId));
        }
    }

    private static GenerationJobCreateResponse notFoundJobResponse(String requirementId) {
        // 构造一个和正常创建接口形状一致的错误响应，前端不用为 404 另写解析逻辑。
        GenerationJobCreateResponse response = new GenerationJobCreateResponse();
        response.setRequirementId(requirementId);
        response.setAssistantMessage("没有找到对应的需求表。");
        return response;
    }

    private static TravelRequirementSpec mergeForUpdate(String requirementId,
                                                        TravelRequirementSpec existing,
                                                        TravelRequirementSpec incoming) {
        // URL 中的 requirementId 优先，避免请求体里的旧 ID 或空 ID 覆盖真实路由目标。
        incoming.setRequirementId(requirementId);
        // 前端编辑表单时可能没有带 sessionId，这里从旧记录补回会话上下文。
        if (!hasText(incoming.getSessionId())) {
            incoming.setSessionId(existing.getSessionId());
        }
        // originalMessage 是最初抽取依据，保留下来便于后续调试和生成提示词。
        if (!hasText(incoming.getOriginalMessage())) {
            incoming.setOriginalMessage(existing.getOriginalMessage());
        }
        // 生成中或已生成状态不允许被一次普通编辑覆盖，防止前端旧数据把状态倒退错乱。
        if (incoming.getStatus() == RequirementStatus.GENERATED
                || incoming.getStatus() == RequirementStatus.GENERATING) {
            incoming.setStatus(existing.getStatus());
        }
        return incoming;
    }

    private static TravelRequirementSpec prepareManualDraft(TravelRequirementSpec incoming) {
        // 手动填写表单时没有 RequirementExtractionAgent 帮忙初始化 ID，这里保持与自然语言 draft 相同的 ID 形状。
        if (!hasText(incoming.getRequirementId())) {
            incoming.setRequirementId("req-" + UUID.randomUUID());
        }
        if (!hasText(incoming.getOriginalMessage())) {
            incoming.setOriginalMessage("手动填写需求表");
        }
        // 手动建表始终从 DRAFT 开始；是否可确认由 RequirementValidationNode 根据字段完整度决定。
        incoming.setStatus(RequirementStatus.DRAFT);
        return incoming;
    }

    private static RequirementDraftResponse buildResponse(TravelRequirementSpec spec,
                                                          RequirementValidation validation) {
        // 所有草稿类接口统一响应形状：需求表、校验结果、给用户看的下一步提示。
        return new RequirementDraftResponse(
                spec == null ? null : spec.getRequirementId(),
                spec,
                validation,
                buildAssistantMessage(validation));
    }

    private static String buildAssistantMessage(RequirementValidation validation) {
        // 没拿到校验结果时使用保守提示，引导用户继续补表而不是直接生成。
        if (validation == null) {
            return "我会先把你的旅行需求整理成表单，请补充关键信息后再生成完整规划。";
        }
        // 已可确认时区分“完全无警告”和“可生成但有注意事项”。
        if (validation.isReadyToConfirm()) {
            if (validation.getWarnings() == null || validation.getWarnings().isEmpty()) {
                return "旅行需求表已经整理完成。请确认无误后点击生成完整规划，本次生成会消耗 1 次额度。";
            }
            return "旅行需求表已经基本完整，但仍有一些需要注意的信息。确认无误后可以生成完整规划。";
        }
        // 未满足确认条件时，把缺失字段拼成自然语言，直接展示给用户补充。
        String missing = validation.getMissingFields() == null || validation.getMissingFields().isEmpty()
                ? "关键信息"
                : String.join("、", validation.getMissingFields());
        return "我已整理出旅行需求表，但还需要补充：" + missing + "。";
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
