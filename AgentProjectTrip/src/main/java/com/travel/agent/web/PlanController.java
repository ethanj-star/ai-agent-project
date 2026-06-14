package com.travel.agent.web;

import com.travel.agent.ai.agents.PlanModificationAgent;
import com.travel.agent.ai.dto.PlanModificationRequest;
import com.travel.agent.ai.dto.PlanModificationResponse;
import com.travel.agent.ai.dto.TravelPlanRecordResponse;
import com.travel.agent.ai.graph.model.PlanLocalRevisionResult;
import com.travel.agent.ai.graph.model.PlanModificationDecision;
import com.travel.agent.ai.graph.model.PlanModificationIntent;
import com.travel.agent.ai.graph.model.RequirementStatus;
import com.travel.agent.ai.graph.model.RequirementValidation;
import com.travel.agent.ai.graph.model.TravelPlanRecord;
import com.travel.agent.ai.graph.model.TravelPlanVersion;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import com.travel.agent.ai.graph.node.PlanLocalRevisionNode;
import com.travel.agent.ai.graph.node.RequirementPatchNode;
import com.travel.agent.ai.graph.node.RequirementValidationNode;
import com.travel.agent.ai.graph.store.RequirementStore;
import com.travel.agent.ai.graph.store.TravelPlanStore;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 已生成旅行计划 HTTP 入口（Web 层 - 第六阶段版本化修改）。
 *
 * <p>系统架构位置：<b>Web 层</b> -> PlanModificationAgent -> PlanLocalRevisionNode / RequirementPatchNode</p>
 *
 * <p>职责：
 * <ul>
 *   <li>按 planId 查询当前计划和历史版本。</li>
 *   <li>接收用户对已有计划的自然语言修改指令。</li>
 *   <li>根据修改意图进入局部重写、需求表更新、追问或普通评论回复。</li>
 *   <li>局部修改成功时新增版本，不覆盖旧版本。</li>
 * </ul>
 * </p>
 */
@RestController
@RequestMapping("/api/v1/plans")
public class PlanController {

    private final TravelPlanStore travelPlanStore;
    private final RequirementStore requirementStore;
    private final PlanModificationAgent modificationAgent;
    private final PlanLocalRevisionNode localRevisionNode;
    private final RequirementPatchNode requirementPatchNode;
    private final RequirementValidationNode requirementValidationNode;

    /**
     * 构造器注入第六阶段计划修改链路依赖。
     *
     * @param travelPlanStore          计划版本仓库
     * @param requirementStore         需求表仓库
     * @param modificationAgent        修改意图识别 Agent
     * @param localRevisionNode        局部计划重写节点
     * @param requirementPatchNode     核心需求补丁合并节点
     * @param requirementValidationNode 需求表校验节点
     */
    public PlanController(TravelPlanStore travelPlanStore,
                          RequirementStore requirementStore,
                          PlanModificationAgent modificationAgent,
                          PlanLocalRevisionNode localRevisionNode,
                          RequirementPatchNode requirementPatchNode,
                          RequirementValidationNode requirementValidationNode) {
        this.travelPlanStore = travelPlanStore;
        this.requirementStore = requirementStore;
        this.modificationAgent = modificationAgent;
        this.localRevisionNode = localRevisionNode;
        this.requirementPatchNode = requirementPatchNode;
        this.requirementValidationNode = requirementValidationNode;
    }

    /**
     * 查询计划当前版本。
     *
     * @param planId 计划 ID
     * @return 当前计划摘要和当前答案
     */
    @GetMapping("/{planId}")
    public ResponseEntity<TravelPlanRecordResponse> getPlan(@PathVariable String planId) {
        // 对外只返回响应 DTO，避免把内部存储字段原样暴露给前端。
        return travelPlanStore.findById(planId)
                .map(record -> ResponseEntity.ok(TravelPlanRecordResponse.from(record)))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 查询指定计划版本。
     *
     * @param planId  计划 ID
     * @param version 版本号
     * @return 指定版本记录
     */
    @GetMapping("/{planId}/versions/{version}")
    public ResponseEntity<TravelPlanVersion> getVersion(@PathVariable String planId,
                                                        @PathVariable int version) {
        // 版本查询用于历史回看：当前版本和旧版本都从 TravelPlanStore 统一读取。
        return travelPlanStore.findVersion(planId, version)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    /**
     * 用自然语言修改已有旅行计划。
     *
     * <p>处理流程：
     * <ol>
     *   <li>读取 planId 对应当前计划。</li>
     *   <li>调用 PlanModificationAgent 判断修改类型。</li>
     *   <li>局部修改：调用 PlanLocalRevisionNode 并新增版本。</li>
     *   <li>核心需求变更：应用 RequirementPatch，保存需求表并要求用户重新确认。</li>
     *   <li>追问或普通评论：返回提示，不新增版本。</li>
     * </ol>
     * </p>
     *
     * @param planId  计划 ID
     * @param request 用户修改请求
     * @return 修改结果
     */
    @PostMapping("/{planId}/modify")
    public ResponseEntity<PlanModificationResponse> modify(@PathVariable String planId,
                                                           @RequestBody PlanModificationRequest request) {
        // 修改指令必须是自然语言文本，否则无法判断是局部重写还是核心需求变更。
        if (request == null || !hasText(request.getMessage())) {
            return ResponseEntity.badRequest().body(errorResponse(planId, "参数 message 不能为空。"));
        }

        // 先定位当前计划；找不到计划就不能构造修改上下文。
        return travelPlanStore.findById(planId)
                .map(record -> handleModification(record, request.getMessage()))
                .orElseGet(() -> ResponseEntity.status(HttpStatus.NOT_FOUND)
                        .body(errorResponse(planId, "没有找到对应的旅行计划。")));
    }

    private ResponseEntity<PlanModificationResponse> handleModification(TravelPlanRecord record, String message) {
        // 先由 Agent 判断用户是在改局部行程、改核心需求、追问，还是普通评论。
        PlanModificationDecision decision = modificationAgent.decide(record, message);
        PlanModificationIntent intent = decision.getIntent();
        if (intent == PlanModificationIntent.LOCAL_REVISION) {
            return handleLocalRevision(record, decision, message);
        }
        if (intent == PlanModificationIntent.REQUIREMENT_CHANGE) {
            return handleRequirementChange(record, decision);
        }
        if (intent == PlanModificationIntent.CLARIFICATION) {
            // 追问信息不足时不创建新版本，只把问题返回给前端继续对话。
            PlanModificationResponse response = baseResponse(record.getPlanId(), intent);
            response.setStatus("NEEDS_CLARIFICATION");
            response.setQuestion(defaultText(decision.getClarificationQuestion(), "你想修改哪一天或哪一部分行程？"));
            response.setAssistantMessage(response.getQuestion());
            return ResponseEntity.ok(response);
        }
        if (intent == PlanModificationIntent.DIRECT_COMMENT) {
            // 普通评论不改变计划内容，例如“好的”“谢谢”，只返回温和确认。
            PlanModificationResponse response = baseResponse(record.getPlanId(), intent);
            response.setStatus("DIRECT_COMMENT");
            response.setAssistantMessage("好的，这版计划先保持不变。你后续可以继续告诉我想改哪一天或哪一部分。");
            return ResponseEntity.ok(response);
        }

        PlanModificationResponse response = baseResponse(record.getPlanId(), intent);
        response.setStatus("UNSUPPORTED");
        response.setAssistantMessage("这个修改类型当前还不支持，请更具体地说明要改哪一天、预算、目的地或住宿偏好。");
        return ResponseEntity.badRequest().body(response);
    }

    private ResponseEntity<PlanModificationResponse> handleLocalRevision(TravelPlanRecord record,
                                                                         PlanModificationDecision decision,
                                                                         String message) {
        // 局部重写只改当前答案文本，不改原始需求表；成功后会追加一个新版本。
        PlanLocalRevisionResult result = localRevisionNode.revise(record, decision, message);
        if (!result.isSuccess()) {
            // 失败时保留当前版本，避免半成品覆盖用户已经看到的计划。
            PlanModificationResponse response = baseResponse(record.getPlanId(), PlanModificationIntent.LOCAL_REVISION);
            response.setStatus("REVISION_FAILED");
            response.setAssistantMessage("局部修改失败，当前计划版本已保留。原因：" + result.getErrorMessage());
            return ResponseEntity.internalServerError().body(response);
        }

        int nextVersion = record.getCurrentVersion() + 1;
        TravelPlanVersion version = new TravelPlanVersion();
        version.setVersion(nextVersion);
        version.setFinalAnswer(result.getAnswer());
        version.setModificationSummary(result.getModificationSummary());
        version.setUserInstruction(message);
        // addVersion 内部会更新 currentVersion，让后续 getPlan 读到最新版。
        travelPlanStore.addVersion(record.getPlanId(), version);

        PlanModificationResponse response = baseResponse(record.getPlanId(), PlanModificationIntent.LOCAL_REVISION);
        response.setStatus("UPDATED");
        response.setVersion(nextVersion);
        response.setAnswer(result.getAnswer());
        response.setAssistantMessage("已根据你的反馈生成 v" + nextVersion + "。");
        return ResponseEntity.ok(response);
    }

    private ResponseEntity<PlanModificationResponse> handleRequirementChange(TravelPlanRecord record,
                                                                            PlanModificationDecision decision) {
        // 核心需求变更会回写需求表，例如换目的地、改预算、改天数，需要重新确认再生成。
        TravelRequirementSpec updatedSpec =
                requirementPatchNode.apply(record.getRequirementSpec(), decision.getRequirementPatch());
        // 标记回 DRAFT，防止用户误以为旧的确认仍然适用于新需求。
        updatedSpec.setStatus(RequirementStatus.DRAFT);
        RequirementValidation validation = requirementValidationNode.validate(updatedSpec);
        requirementStore.save(updatedSpec);

        PlanModificationResponse response = baseResponse(record.getPlanId(), PlanModificationIntent.REQUIREMENT_CHANGE);
        // 如果新需求已完整，只需要用户确认；否则要求先补齐缺失字段。
        response.setStatus(validation.isReadyToConfirm()
                ? "REQUIREMENT_NEEDS_CONFIRMATION"
                : "REQUIREMENT_NEEDS_USER_INPUT");
        response.setRequirementSpec(updatedSpec);
        response.setValidation(validation);
        response.setAssistantMessage(validation.isReadyToConfirm()
                ? "你修改了核心旅行需求，请确认更新后的需求表，再重新生成计划。"
                : "你修改了核心旅行需求，但需求表还缺少信息，请先补全。");
        return ResponseEntity.ok(response);
    }

    private static PlanModificationResponse baseResponse(String planId, PlanModificationIntent intent) {
        PlanModificationResponse response = new PlanModificationResponse();
        response.setPlanId(planId);
        response.setModificationIntent(intent);
        return response;
    }

    private static PlanModificationResponse errorResponse(String planId, String message) {
        PlanModificationResponse response = new PlanModificationResponse();
        response.setPlanId(planId);
        response.setStatus("ERROR");
        response.setAssistantMessage(message);
        return response;
    }

    private static String defaultText(String value, String fallback) {
        return hasText(value) ? value : fallback;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
