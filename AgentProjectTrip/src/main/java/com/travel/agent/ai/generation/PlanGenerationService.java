package com.travel.agent.ai.generation;

import com.travel.agent.ai.dto.GatekeeperResponse;
import com.travel.agent.ai.graph.LangGraphPlannerFacade;
import com.travel.agent.ai.graph.model.GenerationJobStage;
import com.travel.agent.ai.graph.model.GraphInputRequest;
import com.travel.agent.ai.graph.model.GraphResult;
import com.travel.agent.ai.graph.model.RequirementStatus;
import com.travel.agent.ai.graph.model.RequirementValidation;
import com.travel.agent.ai.graph.model.TravelPlanRecord;
import com.travel.agent.ai.graph.model.TravelPlanVersion;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import com.travel.agent.ai.graph.node.RequirementValidationNode;
import com.travel.agent.ai.graph.store.RequirementStore;
import com.travel.agent.ai.graph.store.TravelPlanStore;
import com.travel.agent.core.service.CreditService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * 完整旅行规划生成业务服务。
 *
 * <p>系统架构位置：RequirementController / AsyncPlanGenerationService -> <b>PlanGenerationService</b> -> LangGraphPlannerFacade</p>
 *
 * <p>职责：
 * <ul>
 *   <li>承接第五阶段同步生成接口原本放在 Controller 内的核心业务。</li>
 *   <li>统一执行需求校验、确认状态检查、扣费、Graph 生成、计划保存和失败退款。</li>
 *   <li>让同步接口和第八阶段异步任务复用同一条生成逻辑，避免两个入口行为不一致。</li>
 * </ul>
 * </p>
 */
@Service
public class PlanGenerationService {

    private static final Logger log = LoggerFactory.getLogger(PlanGenerationService.class);

    /** 需求表校验节点，负责生成前的字段完整性门控。 */
    private final RequirementValidationNode validationNode;

    /** 需求表仓库，负责更新 GENERATING / GENERATED / CONFIRMED 状态。 */
    private final RequirementStore requirementStore;

    /** 旅行计划仓库，负责保存成功生成的第一版 TravelPlanRecord。 */
    private final TravelPlanStore travelPlanStore;

    /** 生成额度服务，负责扣除和失败退款。 */
    private final CreditService creditService;

    /** 核心 LangGraph 规划门面。 */
    private final LangGraphPlannerFacade plannerFacade;

    /**
     * 构造完整规划生成服务。
     *
     * @param validationNode  需求表校验节点
     * @param requirementStore 需求表仓库
     * @param travelPlanStore 旅行计划仓库
     * @param creditService   生成额度服务
     * @param plannerFacade   LangGraph 规划门面
     */
    public PlanGenerationService(RequirementValidationNode validationNode,
                                 RequirementStore requirementStore,
                                 TravelPlanStore travelPlanStore,
                                 CreditService creditService,
                                 LangGraphPlannerFacade plannerFacade) {
        this.validationNode = validationNode;
        this.requirementStore = requirementStore;
        this.travelPlanStore = travelPlanStore;
        this.creditService = creditService;
        this.plannerFacade = plannerFacade;
    }

    /**
     * 同步执行完整旅行规划生成。
     *
     * <p>这是旧同步接口使用的便捷方法；异步任务会调用带阶段回调的重载版本。</p>
     *
     * @param spec 已确认或待校验的结构化需求表
     * @return 完整生成结果
     */
    public PlanGenerationOutcome generate(TravelRequirementSpec spec) {
        return generate(spec, ignored -> {
        });
    }

    /**
     * 执行完整旅行规划生成，并在关键步骤回调当前阶段。
     *
     * <p>处理流程：
     * <ol>
     *   <li>校验结构化需求表。</li>
     *   <li>确认状态必须是 CONFIRMED。</li>
     *   <li>扣除一次生成额度。</li>
     *   <li>调用 LangGraphPlannerFacade 生成方案。</li>
     *   <li>成功时保存 TravelPlanRecord，失败时退回额度并恢复需求表状态。</li>
     * </ol>
     * </p>
     *
     * @param spec          已确认或待校验的结构化需求表
     * @param stageConsumer 阶段回调，异步任务用它更新 generation_jobs.current_stage
     * @return 完整生成结果
     */
    public PlanGenerationOutcome generate(TravelRequirementSpec spec,
                                          Consumer<GenerationJobStage> stageConsumer) {
        // 异步入口会传入阶段回调；同步入口传空时用 no-op，保证两条入口复用同一套业务逻辑。
        Consumer<GenerationJobStage> stage = stageConsumer == null ? ignored -> {
        } : stageConsumer;
        if (spec == null) {
            return rejected(null,
                    null,
                    0,
                    400,
                    GraphResult.failure("需求表不能为空。", "Requirement spec is null"));
        }

        // Step 1：生成前先校验结构化需求表。字段不完整时不要扣费，也不要进入 Graph。
        stage.accept(GenerationJobStage.VALIDATING_REQUIREMENT);
        RequirementValidation validation = validationNode.validate(spec);
        if (!validation.isReadyToConfirm()) {
            return rejected(spec,
                    spec.getStatus(),
                    creditService.getRemainingCredits(spec.getSessionId()),
                    400,
                    GraphResult.failure("需求表还没有补全，暂时不能生成完整规划。", "Requirement validation failed"));
        }
        // 需求表必须由用户确认过，防止用户还在编辑草稿时误触发高成本生成。
        if (spec.getStatus() != RequirementStatus.CONFIRMED) {
            return rejected(spec,
                    spec.getStatus(),
                    creditService.getRemainingCredits(spec.getSessionId()),
                    400,
                    GraphResult.failure("请先确认需求表，再生成完整规划。", "Requirement not confirmed"));
        }

        // Step 2：先扣费再生成。失败时 refundAndRestore 会退回额度并恢复 CONFIRMED 状态。
        stage.accept(GenerationJobStage.CHARGING_CREDIT);
        if (!creditService.consumeGenerationCredit(spec.getSessionId())) {
            return rejected(spec,
                    spec.getStatus(),
                    0,
                    402,
                    GraphResult.failure("当前生成额度不足，请购买或充值后再生成完整规划。", "Insufficient credits"));
        }

        // 标记 GENERATING，前端和异步任务都可以看到“已经进入生成中”的业务状态。
        spec.setStatus(RequirementStatus.GENERATING);
        requirementStore.save(spec);

        // Step 3：调用 Graph 生成完整方案。Graph 内部负责 RAG、分支 Agent、规划和风险审查。
        stage.accept(GenerationJobStage.RUNNING_GRAPH);
        GraphResult graphResult = runGraph(spec);
        if (!graphResult.isSuccess()) {
            return refundAndRestore(spec, stage, graphResult);
        }

        // Step 4：Graph 成功后保存第一版计划记录；只有保存成功才认为本次生成真正完成。
        stage.accept(GenerationJobStage.SAVING_PLAN);
        try {
            TravelPlanRecord record = buildInitialPlanRecord(spec, graphResult);
            travelPlanStore.save(record);
            spec.setStatus(RequirementStatus.GENERATED);
            requirementStore.save(spec);
            log.info("[PlanGeneration] plan record saved, requirementId={}, planId={}",
                    spec.getRequirementId(), record.getPlanId());
            return accepted(spec, record.getPlanId(), graphResult, true, false);
        } catch (Exception e) {
            log.error("[PlanGeneration] plan save failed, requirementId={}, error={}",
                    spec.getRequirementId(), e.getMessage());
            // Graph 已生成但保存失败时也要退款，否则用户付费却拿不到 planId。
            GraphResult failure = GraphResult.failure("完整规划保存失败，已退回本次生成额度，请稍后重试。", e.getMessage());
            return refundAndRestore(spec, stage, failure);
        }
    }

    private GraphResult runGraph(TravelRequirementSpec spec) {
        try {
            // 将已确认需求表转换成 GraphInputRequest，复用原有 PLAN_OR_RAG 工作流。
            return plannerFacade.plan(buildGraphRequest(spec));
        } catch (Exception e) {
            // Facade 理论上会自己吞掉异常并返回 GraphResult；这里是外层保险。
            log.error("[PlanGeneration] graph generation failed, requirementId={}, error={}",
                    spec.getRequirementId(), e.getMessage());
            return GraphResult.failure("完整规划生成失败，请稍后重试。", e.getMessage());
        }
    }

    private PlanGenerationOutcome refundAndRestore(TravelRequirementSpec spec,
                                                   Consumer<GenerationJobStage> stage,
                                                   GraphResult graphResult) {
        // 生成失败统一走这里：退款、恢复需求表为 CONFIRMED，让用户可以修改后重新生成。
        stage.accept(GenerationJobStage.REFUNDING_CREDIT);
        creditService.refundGenerationCredit(spec.getSessionId());
        spec.setStatus(RequirementStatus.CONFIRMED);
        requirementStore.save(spec);
        return accepted(spec, null, graphResult, false, true);
    }

    private PlanGenerationOutcome rejected(TravelRequirementSpec spec,
                                           RequirementStatus status,
                                           int remainingCredits,
                                           int httpStatus,
                                           GraphResult graphResult) {
        // 生成前置条件不满足时返回 rejected；没有扣费，也不会创建计划记录。
        PlanGenerationOutcome outcome = new PlanGenerationOutcome();
        outcome.setRequirementId(spec == null ? null : spec.getRequirementId());
        outcome.setStatus(status);
        outcome.setRemainingCredits(remainingCredits);
        outcome.setHttpStatusCode(httpStatus);
        outcome.setGraphResult(graphResult);
        outcome.setCreditCharged(false);
        outcome.setRefunded(false);
        return outcome;
    }

    private PlanGenerationOutcome accepted(TravelRequirementSpec spec,
                                           String planId,
                                           GraphResult graphResult,
                                           boolean creditCharged,
                                           boolean refunded) {
        // accepted 表示“生成流程已经结束并形成业务结果”，结果可能是成功计划，也可能是已退款失败。
        PlanGenerationOutcome outcome = new PlanGenerationOutcome();
        outcome.setRequirementId(spec.getRequirementId());
        outcome.setPlanId(planId);
        outcome.setStatus(spec.getStatus());
        outcome.setRemainingCredits(creditService.getRemainingCredits(spec.getSessionId()));
        outcome.setHttpStatusCode(200);
        outcome.setGraphResult(graphResult);
        outcome.setCreditCharged(creditCharged);
        outcome.setRefunded(refunded);
        return outcome;
    }

    private static TravelPlanRecord buildInitialPlanRecord(TravelRequirementSpec spec, GraphResult graphResult) {
        // 新计划从 version=1 开始；后续用户局部修改会继续向同一个 record 追加版本。
        TravelPlanRecord record = new TravelPlanRecord();
        record.setPlanId("plan-" + UUID.randomUUID());
        record.setRequirementId(spec.getRequirementId());
        record.setSessionId(spec.getSessionId());
        record.setRequirementSpec(spec);

        TravelPlanVersion version = new TravelPlanVersion();
        version.setVersion(1);
        version.setFinalAnswer(graphResult.getAnswer());
        version.setValidationIssues(graphResult.getValidationIssues());
        version.setModificationSummary("初始完整规划");
        version.setUserInstruction(spec.getOriginalMessage());
        record.addVersion(version);
        return record;
    }

    private static GraphInputRequest buildGraphRequest(TravelRequirementSpec spec) {
        // 旧 Graph 入口依赖 GatekeeperResponse，这里手动构造一个等价的 PLAN_OR_RAG 路由结果。
        GatekeeperResponse route = new GatekeeperResponse();
        route.setIntent("PLAN_OR_RAG");
        GatekeeperResponse.Entities entities = new GatekeeperResponse.Entities();
        entities.setLocations(spec.getDestinations());
        entities.setTime(resolveTravelTime(spec));
        entities.setKeywords(buildKeywords(spec));
        route.setEntities(entities);

        GraphInputRequest request = new GraphInputRequest(synthesizeUserQuery(spec), route, spec.getSessionId());
        // 同时把完整结构化需求表塞进 request，让新节点优先读取强类型字段。
        request.setRequirementSpec(spec);
        return request;
    }

    private static String synthesizeUserQuery(TravelRequirementSpec spec) {
        // 给旧 Planner/RAG 节点合成一段自然语言摘要，兼容还没有完全改造为强类型读取的节点。
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
        // keywords 仍被 BranchDispatch 和部分校验逻辑读取，因此从结构化字段同步一份轻量关键词。
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
        // 优先保留用户原始时间表达，例如“国庆”“下个月”；没有自然语言文本时再使用标准日期。
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
