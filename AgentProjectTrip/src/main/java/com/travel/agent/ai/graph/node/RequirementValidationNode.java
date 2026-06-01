package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.RequirementStatus;
import com.travel.agent.ai.graph.model.RequirementValidation;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/**
 * 结构化需求表校验节点（Graph 层 - 生成前门控规则）。
 *
 * <p>系统架构位置：RequirementExtractionAgent / RequirementController -> <b>RequirementValidationNode</b> -> GenerationGate</p>
 *
 * <p>职责：
 * <ul>
 *   <li>读取第五阶段抽取出的 {@link TravelRequirementSpec}。</li>
 *   <li>检查目的地、时长、预算、币种、国际机票边界等关键字段是否完整。</li>
 *   <li>把阻塞字段写入 {@link RequirementValidation}，避免信息不足时进入高成本规划链路。</li>
 *   <li>把校验结果同步回需求表状态，方便前端直接展示。</li>
 * </ul>
 * </p>
 */
@Component
public class RequirementValidationNode {

    /**
     * 校验结构化旅行需求表。
     *
     * <p>处理流程：
     * <ol>
     *   <li>检查硬阻塞字段：目的地、天数、预算金额、预算币种、国际机票边界。</li>
     *   <li>检查强建议字段：旅行人数、出发城市、出行时间。</li>
     *   <li>将缺失字段、阻塞原因和非阻塞警告写入 RequirementValidation。</li>
     *   <li>同步更新 spec.status、spec.missingFields 和 spec.warnings。</li>
     * </ol>
     * </p>
     *
     * @param spec 结构化旅行需求表，可为空
     * @return 校验结果；spec 为空时返回阻塞结果
     */
    public RequirementValidation validate(TravelRequirementSpec spec) {
        RequirementValidation validation = new RequirementValidation();
        List<String> missingFields = new ArrayList<>();
        List<String> blockingReasons = new ArrayList<>();
        List<String> warnings = new ArrayList<>();

        if (spec == null) {
            missingFields.add("spec");
            blockingReasons.add("需求表为空，无法生成完整规划。");
            validation.setComplete(false);
            validation.setReadyToConfirm(false);
            validation.setMissingFields(missingFields);
            validation.setBlockingReasons(blockingReasons);
            return validation;
        }

        validateDestinations(spec, missingFields, blockingReasons);
        validateDuration(spec, missingFields, blockingReasons);
        validateBudget(spec, missingFields, blockingReasons);
        validateFlightBudgetBoundary(spec, missingFields, blockingReasons);
        validateSoftFields(spec, missingFields, warnings);
        validatePlausibility(spec, warnings);

        boolean complete = blockingReasons.isEmpty();
        validation.setComplete(complete);
        validation.setReadyToConfirm(complete);
        validation.setMissingFields(missingFields);
        validation.setBlockingReasons(blockingReasons);
        validation.setWarnings(warnings);

        spec.setMissingFields(missingFields);
        spec.setWarnings(warnings);
        if (spec.getStatus() != RequirementStatus.GENERATING
                && spec.getStatus() != RequirementStatus.GENERATED) {
            if (!complete) {
                spec.setStatus(RequirementStatus.NEEDS_USER_INPUT);
            } else if (spec.getStatus() != RequirementStatus.CONFIRMED) {
                spec.setStatus(RequirementStatus.READY_TO_CONFIRM);
            }
        }
        return validation;
    }

    private static void validateDestinations(TravelRequirementSpec spec,
                                             List<String> missingFields,
                                             List<String> blockingReasons) {
        if (spec.getDestinations() == null || spec.getDestinations().isEmpty()) {
            missingFields.add("destinations");
            blockingReasons.add("请补充至少一个明确国家或城市。");
            return;
        }
        if (hasOnlyBroadDestination(spec.getDestinations())) {
            missingFields.add("destinations");
            blockingReasons.add("目的地仍然过宽泛，请把“欧洲”等范围细化为国家或城市。");
        }
    }

    private static void validateDuration(TravelRequirementSpec spec,
                                         List<String> missingFields,
                                         List<String> blockingReasons) {
        if (spec.getDurationDays() == null || spec.getDurationDays() <= 0) {
            missingFields.add("durationDays");
            blockingReasons.add("请补充行程天数，例如 7 天或 10 天。");
        }
    }

    private static void validateBudget(TravelRequirementSpec spec,
                                       List<String> missingFields,
                                       List<String> blockingReasons) {
        if (spec.getBudgetAmount() == null || spec.getBudgetAmount().compareTo(BigDecimal.ZERO) <= 0) {
            missingFields.add("budgetAmount");
            blockingReasons.add("请补充预算金额。");
        }
        if (!hasText(spec.getBudgetCurrency())) {
            missingFields.add("budgetCurrency");
            blockingReasons.add("请确认预算币种，例如 EUR、CNY、USD。");
        }
    }

    private static void validateFlightBudgetBoundary(TravelRequirementSpec spec,
                                                     List<String> missingFields,
                                                     List<String> blockingReasons) {
        if (spec.getBudgetIncludesInternationalFlight() == null) {
            missingFields.add("budgetIncludesInternationalFlight");
            blockingReasons.add("请确认预算是否包含国际机票，避免后续预算误算。");
        }
    }

    private static void validateSoftFields(TravelRequirementSpec spec,
                                           List<String> missingFields,
                                           List<String> warnings) {
        if (spec.getTravelerCount() == null || spec.getTravelerCount() <= 0) {
            missingFields.add("travelerCount");
            warnings.add("未提供旅行人数，系统将难以准确估算住宿、餐饮和门票预算。");
        }
        if (!hasText(spec.getDepartureCity())) {
            missingFields.add("departureCity");
            warnings.add("未提供出发城市，入境城市和交通衔接只能作为假设处理。");
        }
        if (!hasText(spec.getStartDateText()) && spec.getStartDate() == null) {
            missingFields.add("startDateText");
            warnings.add("未提供出行时间，旺季、人流、天气和预约建议只能泛化处理。");
        }
    }

    private static void validatePlausibility(TravelRequirementSpec spec, List<String> warnings) {
        if (spec.getBudgetAmount() != null
                && "EUR".equalsIgnoreCase(spec.getBudgetCurrency())
                && spec.getDurationDays() != null
                && spec.getDurationDays() >= 10
                && spec.getBudgetAmount().compareTo(BigDecimal.valueOf(800)) < 0) {
            warnings.add("预算对 10 天以上欧洲行程偏紧，后续方案可能需要青年旅舍、自炊和慢车。");
        }
        if (spec.getDestinations() != null
                && spec.getDurationDays() != null
                && spec.getDestinations().size() >= 4
                && spec.getDurationDays() <= 7) {
            warnings.add("目的地数量较多但天数较短，后续行程可能过赶。");
        }
    }

    private static boolean hasOnlyBroadDestination(List<String> destinations) {
        if (destinations == null || destinations.isEmpty()) {
            return false;
        }
        for (String destination : destinations) {
            if (!isBroadDestination(destination)) {
                return false;
            }
        }
        return true;
    }

    private static boolean isBroadDestination(String destination) {
        if (!hasText(destination)) {
            return true;
        }
        String normalized = destination.trim();
        return normalized.equals("欧洲")
                || normalized.equals("欧州")
                || normalized.equalsIgnoreCase("europe")
                || normalized.equals("国外")
                || normalized.equals("海外")
                || normalized.equals("境外")
                || normalized.equals("随便")
                || normalized.equals("都可以");
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
