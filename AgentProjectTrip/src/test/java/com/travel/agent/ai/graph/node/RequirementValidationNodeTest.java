package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.RequirementStatus;
import com.travel.agent.ai.graph.model.RequirementValidation;
import com.travel.agent.ai.graph.model.TravelRequirementSpec;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * RequirementValidationNode 的单元测试。
 *
 * <p>重点验证生成门控规则：目的地、时长、预算和币种是硬阻塞；
 * 国际机票边界、出发地和时间精度只作为 warning 提醒用户确认。</p>
 */
class RequirementValidationNodeTest {

    private final RequirementValidationNode node = new RequirementValidationNode();

    /**
     * 字段完整时，需求表应进入 READY_TO_CONFIRM。
     */
    @Test
    void validateMarksCompleteSpecReadyToConfirm() {
        TravelRequirementSpec spec = completeSpec();

        RequirementValidation validation = node.validate(spec);

        assertThat(validation.isReadyToConfirm()).isTrue();
        assertThat(validation.getBlockingReasons()).isEmpty();
        assertThat(spec.getStatus()).isEqualTo(RequirementStatus.READY_TO_CONFIRM);
    }

    /**
     * 目的地只有“欧洲”时，应阻塞生成，防止系统直接产出随意路线。
     */
    @Test
    void validateBlocksBroadDestination() {
        TravelRequirementSpec spec = completeSpec();
        spec.setDestinations(List.of("欧洲"));

        RequirementValidation validation = node.validate(spec);

        assertThat(validation.isReadyToConfirm()).isFalse();
        assertThat(validation.getMissingFields()).contains("destinations");
        assertThat(validation.getBlockingReasons()).anyMatch(reason -> reason.contains("过宽泛"));
        assertThat(spec.getStatus()).isEqualTo(RequirementStatus.NEEDS_USER_INPUT);
    }

    /**
     * 预算是否包含国际机票不明确时，只提示风险，不阻塞确认。
     */
    @Test
    void validateWarnsUnknownInternationalFlightBoundary() {
        TravelRequirementSpec spec = completeSpec();
        spec.setBudgetIncludesInternationalFlight(null);

        RequirementValidation validation = node.validate(spec);

        assertThat(validation.isReadyToConfirm()).isTrue();
        assertThat(validation.getMissingFields()).contains("budgetIncludesInternationalFlight");
        assertThat(validation.getWarnings()).anyMatch(warning -> warning.contains("国际机票"));
        assertThat(validation.getBlockingReasons()).isEmpty();
    }

    /**
     * 已确认需求表如果被编辑成不完整，应回退到 NEEDS_USER_INPUT。
     */
    @Test
    void validateConfirmedSpecFallsBackWhenEditedInvalid() {
        TravelRequirementSpec spec = completeSpec();
        spec.setStatus(RequirementStatus.CONFIRMED);
        spec.setDurationDays(null);

        RequirementValidation validation = node.validate(spec);

        assertThat(validation.isReadyToConfirm()).isFalse();
        assertThat(spec.getStatus()).isEqualTo(RequirementStatus.NEEDS_USER_INPUT);
    }

    private static TravelRequirementSpec completeSpec() {
        TravelRequirementSpec spec = new TravelRequirementSpec();
        spec.setRequirementId("req-1");
        spec.setSessionId("s1");
        spec.setDestinations(List.of("法国", "意大利"));
        spec.setStartDateText("国庆");
        spec.setDurationDays(10);
        spec.setTravelerCount(2);
        spec.setDepartureCity("上海");
        spec.setBudgetAmount(BigDecimal.valueOf(1200));
        spec.setBudgetCurrency("EUR");
        spec.setBudgetIncludesInternationalFlight(false);
        return spec;
    }
}
