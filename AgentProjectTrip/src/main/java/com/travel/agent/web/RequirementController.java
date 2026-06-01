package com.travel.agent.web;

import com.travel.agent.ai.agents.RequirementExtractionAgent;
import com.travel.agent.ai.dto.GatekeeperResponse;
import com.travel.agent.ai.dto.RequirementConfirmRequest;
import com.travel.agent.ai.dto.RequirementDraftRequest;
import com.travel.agent.ai.dto.RequirementDraftResponse;
import com.travel.agent.ai.dto.RequirementGenerateResponse;
import com.travel.agent.ai.graph.LangGraphPlannerFacade;
import com.travel.agent.ai.graph.model.GraphInputRequest;
import com.travel.agent.ai.graph.model.GraphResult;
import com.travel.agent.ai.graph.model.RequirementStatus;
import com.travel.agent.ai.graph.model.RequirementValidation;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import com.travel.agent.ai.graph.node.RequirementValidationNode;
import com.travel.agent.ai.graph.store.RequirementStore;
import com.travel.agent.core.service.CreditService;
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

import java.util.ArrayList;
import java.util.List;

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
    private final CreditService creditService;
    private final LangGraphPlannerFacade plannerFacade;

    /**
     * 构造器注入第五阶段入口需要的服务。
     *
     * @param extractionAgent 自然语言需求抽取 Agent
     * @param validationNode  需求表校验节点
     * @param requirementStore 需求表仓库
     * @param creditService   模拟生成额度服务
     * @param plannerFacade   前四阶段完整规划 Graph 门面
     */
    public RequirementController(RequirementExtractionAgent extractionAgent,
                                 RequirementValidationNode validationNode,
                                 RequirementStore requirementStore,
                                 CreditService creditService,
                                 LangGraphPlannerFacade plannerFacade) {
        this.extractionAgent = extractionAgent;
        this.validationNode = validationNode;
        this.requirementStore = requirementStore;
        this.creditService = creditService;
        this.plannerFacade = plannerFacade;
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
        if (request == null || !hasText(request.getMessage())) {
            return ResponseEntity.badRequest().body(new RequirementDraftResponse(
                    null,
                    null,
                    null,
                    "参数 message 不能为空。"));
        }

        TravelRequirementSpec spec = extractionAgent.extract(request.getSessionId(), request.getMessage());
        RequirementValidation validation = validationNode.validate(spec);
        requirementStore.save(spec);
        log.info("[Requirement] draft created, requirementId={}, status={}",
                spec.getRequirementId(), spec.getStatus());

        return ResponseEntity.ok(buildResponse(spec, validation));
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

        TravelRequirementSpec merged = requirementStore.findById(requirementId)
                .map(existing -> mergeForUpdate(requirementId, existing, incoming))
                .orElseGet(() -> {
                    incoming.setRequirementId(requirementId);
                    return incoming;
                });
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
        return requirementStore.findById(requirementId)
                .map(existing -> {
                    TravelRequirementSpec spec = request != null && request.getSpec() != null
                            ? mergeForUpdate(requirementId, existing, request.getSpec())
                            : existing;
                    RequirementValidation validation = validationNode.validate(spec);
                    if (!validation.isReadyToConfirm()) {
                        requirementStore.save(spec);
                        return ResponseEntity.badRequest().body(buildResponse(spec, validation));
                    }
                    spec.setStatus(RequirementStatus.CONFIRMED);
                    requirementStore.save(spec);
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
     * <p>处理流程：
     * <ol>
     *   <li>读取需求表并确认状态为 CONFIRMED。</li>
     *   <li>消耗一次模拟生成额度；额度不足时不进入 Graph。</li>
     *   <li>将 TravelRequirementSpec 注入 GraphInputRequest。</li>
     *   <li>调用 LangGraphPlannerFacade 复用 RAG、Branch、Planner、Risk 和 Revision 流程。</li>
     *   <li>生成失败时退还额度，并把状态恢复为 CONFIRMED。</li>
     * </ol>
     * </p>
     *
     * @param requirementId 需求表 ID
     * @return 完整规划结果和剩余额度
     */
    @PostMapping("/{requirementId}/generate")
    public ResponseEntity<RequirementGenerateResponse> generate(@PathVariable String requirementId) {
        return requirementStore.findById(requirementId)
                .map(this::generateFromSpec)
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND).body(
                        new RequirementGenerateResponse(
                                requirementId,
                                null,
                                0,
                                GraphResult.failure("没有找到对应的需求表。", "Requirement not found"))));
    }

    private ResponseEntity<RequirementGenerateResponse> generateFromSpec(TravelRequirementSpec spec) {
        RequirementValidation validation = validationNode.validate(spec);
        if (!validation.isReadyToConfirm()) {
            return ResponseEntity.badRequest().body(new RequirementGenerateResponse(
                    spec.getRequirementId(),
                    spec.getStatus(),
                    creditService.getRemainingCredits(spec.getSessionId()),
                    GraphResult.failure("需求表还没有补全，暂时不能生成完整规划。", "Requirement validation failed")));
        }
        if (spec.getStatus() != RequirementStatus.CONFIRMED) {
            return ResponseEntity.badRequest().body(new RequirementGenerateResponse(
                    spec.getRequirementId(),
                    spec.getStatus(),
                    creditService.getRemainingCredits(spec.getSessionId()),
                    GraphResult.failure("请先确认需求表，再生成完整规划。", "Requirement not confirmed")));
        }
        if (!creditService.consumeGenerationCredit(spec.getSessionId())) {
            return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED).body(new RequirementGenerateResponse(
                    spec.getRequirementId(),
                    spec.getStatus(),
                    0,
                    GraphResult.failure("当前生成额度不足，请购买或充值后再生成完整规划。", "Insufficient credits")));
        }

        spec.setStatus(RequirementStatus.GENERATING);
        requirementStore.save(spec);

        GraphResult graphResult;
        try {
            graphResult = plannerFacade.plan(buildGraphRequest(spec));
        } catch (Exception e) {
            log.error("[Requirement] graph generation failed, requirementId={}, error={}",
                    spec.getRequirementId(), e.getMessage());
            graphResult = GraphResult.failure("完整规划生成失败，请稍后重试。", e.getMessage());
        }

        if (graphResult.isSuccess()) {
            spec.setStatus(RequirementStatus.GENERATED);
        } else {
            creditService.refundGenerationCredit(spec.getSessionId());
            spec.setStatus(RequirementStatus.CONFIRMED);
        }
        requirementStore.save(spec);

        return ResponseEntity.ok(new RequirementGenerateResponse(
                spec.getRequirementId(),
                spec.getStatus(),
                creditService.getRemainingCredits(spec.getSessionId()),
                graphResult));
    }

    private static GraphInputRequest buildGraphRequest(TravelRequirementSpec spec) {
        GatekeeperResponse route = new GatekeeperResponse();
        route.setIntent("PLAN_OR_RAG");
        GatekeeperResponse.Entities entities = new GatekeeperResponse.Entities();
        entities.setLocations(spec.getDestinations());
        entities.setTime(resolveTravelTime(spec));
        entities.setKeywords(buildKeywords(spec));
        route.setEntities(entities);

        GraphInputRequest request = new GraphInputRequest(synthesizeUserQuery(spec), route, spec.getSessionId());
        request.setRequirementSpec(spec);
        return request;
    }

    private static TravelRequirementSpec mergeForUpdate(String requirementId,
                                                        TravelRequirementSpec existing,
                                                        TravelRequirementSpec incoming) {
        incoming.setRequirementId(requirementId);
        if (!hasText(incoming.getSessionId())) {
            incoming.setSessionId(existing.getSessionId());
        }
        if (!hasText(incoming.getOriginalMessage())) {
            incoming.setOriginalMessage(existing.getOriginalMessage());
        }
        if (incoming.getStatus() == RequirementStatus.GENERATED
                || incoming.getStatus() == RequirementStatus.GENERATING) {
            incoming.setStatus(existing.getStatus());
        }
        return incoming;
    }

    private static RequirementDraftResponse buildResponse(TravelRequirementSpec spec,
                                                          RequirementValidation validation) {
        return new RequirementDraftResponse(
                spec == null ? null : spec.getRequirementId(),
                spec,
                validation,
                buildAssistantMessage(validation));
    }

    private static String buildAssistantMessage(RequirementValidation validation) {
        if (validation == null) {
            return "我会先把你的旅行需求整理成表单，请补充关键信息后再生成完整规划。";
        }
        if (validation.isReadyToConfirm()) {
            if (validation.getWarnings() == null || validation.getWarnings().isEmpty()) {
                return "旅行需求表已经整理完成。请确认无误后点击生成完整规划，本次生成会消耗 1 次额度。";
            }
            return "旅行需求表已经基本完整，但仍有一些需要注意的信息。确认无误后可以生成完整规划。";
        }
        String missing = validation.getMissingFields() == null || validation.getMissingFields().isEmpty()
                ? "关键信息"
                : String.join("、", validation.getMissingFields());
        return "我已整理出旅行需求表，但还需要补充：" + missing + "。";
    }

    private static String synthesizeUserQuery(TravelRequirementSpec spec) {
        List<String> parts = new ArrayList<>();
        if (spec.getDestinations() != null && !spec.getDestinations().isEmpty()) {
            parts.add("目的地：" + String.join("、", spec.getDestinations()));
        }
        if (hasText(resolveTravelTime(spec))) {
            parts.add("出行时间：" + resolveTravelTime(spec));
        }
        if (spec.getDurationDays() != null) {
            parts.add("行程时长：" + spec.getDurationDays() + "天");
        }
        if (spec.getBudgetAmount() != null) {
            parts.add("预算：" + spec.getBudgetAmount().stripTrailingZeros().toPlainString()
                    + defaultText(spec.getBudgetCurrency(), ""));
        }
        if (spec.getBudgetIncludesInternationalFlight() != null) {
            parts.add(spec.getBudgetIncludesInternationalFlight() ? "预算包含国际机票" : "预算不含国际机票");
        }
        if (spec.getTravelerCount() != null) {
            parts.add("人数：" + spec.getTravelerCount() + "人");
        }
        if (hasText(spec.getDepartureCity())) {
            parts.add("出发地：" + spec.getDepartureCity());
        }
        if (spec.getPreferences() != null && !spec.getPreferences().isEmpty()) {
            parts.add("偏好：" + String.join("、", spec.getPreferences()));
        }
        if (spec.getAvoidances() != null && !spec.getAvoidances().isEmpty()) {
            parts.add("避开：" + String.join("、", spec.getAvoidances()));
        }
        return "请根据已确认的结构化旅行需求生成完整行程。"
                + (parts.isEmpty() ? "" : " " + String.join("；", parts));
    }

    private static List<String> buildKeywords(TravelRequirementSpec spec) {
        List<String> keywords = new ArrayList<>();
        if (spec.getDurationDays() != null) {
            keywords.add(spec.getDurationDays() + "天");
        }
        if (spec.getBudgetAmount() != null) {
            keywords.add("预算" + spec.getBudgetAmount().stripTrailingZeros().toPlainString()
                    + defaultText(spec.getBudgetCurrency(), ""));
        }
        if (spec.getBudgetIncludesInternationalFlight() != null) {
            keywords.add(spec.getBudgetIncludesInternationalFlight() ? "包含国际机票" : "不含国际机票");
        }
        if (spec.getPreferences() != null) {
            keywords.addAll(spec.getPreferences());
        }
        if (spec.getAvoidances() != null) {
            keywords.addAll(spec.getAvoidances());
        }
        if (hasText(spec.getTravelStyle())) {
            keywords.add(spec.getTravelStyle());
        }
        if (hasText(spec.getAccommodationPreference())) {
            keywords.add(spec.getAccommodationPreference());
        }
        if (hasText(spec.getTransportPreference())) {
            keywords.add(spec.getTransportPreference());
        }
        return keywords;
    }

    private static String resolveTravelTime(TravelRequirementSpec spec) {
        if (hasText(spec.getStartDateText())) {
            return spec.getStartDateText();
        }
        return spec.getStartDate() == null ? null : spec.getStartDate().toString();
    }

    private static String defaultText(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
