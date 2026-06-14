package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.BranchTask;
import com.travel.agent.ai.graph.model.BranchTaskType;
import com.travel.agent.ai.graph.model.FlightSearchRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * FlightSearchParamResolver 的单元测试。
 *
 * <p>只验证航班工具参数推导，不访问真实 SerpApi。</p>
 */
class FlightSearchParamResolverTest {

    /**
     * 结构化出发地、目的地和日期齐全时，应解析成可查询的 IATA 参数。
     */
    @Test
    void resolveReturnsQueryableRequestForStructuredTask() {
        BranchTask task = new BranchTask("flight-1", BranchTaskType.FLIGHT, "查航班",
                List.of("法国"), "2026-10-01", List.of(),
                LocalDate.of(2026, 10, 1), 10, "上海", null, true);

        FlightSearchRequest request = FlightSearchParamResolver.resolve(task);

        assertThat(request.queryable()).isTrue();
        assertThat(request.originCode()).isEqualTo("PVG");
        assertThat(request.destinationCode()).isEqualTo("CDG");
        assertThat(request.departureDate()).isEqualTo("2026-10-01");
    }

    /**
     * 缺少出发地时，应返回不可查询请求，而不是猜测用户从哪里出发。
     */
    @Test
    void resolveReturnsMissingWhenDepartureCityAbsent() {
        BranchTask task = new BranchTask("flight-1", BranchTaskType.FLIGHT, "查航班",
                List.of("法国"), "2026-10-01", List.of(),
                LocalDate.of(2026, 10, 1), 10, null, null, true);

        FlightSearchRequest request = FlightSearchParamResolver.resolve(task);

        assertThat(request.queryable()).isFalse();
        assertThat(request.missingReason()).contains("出发地");
    }

    /**
     * 自然语言中的中文日期可以作为旧入口的轻量兜底。
     */
    @Test
    void resolveParsesChineseDateFromQuery() {
        BranchTask task = new BranchTask("flight-1", BranchTaskType.FLIGHT,
                "从上海出发，2026年10月1日去法国",
                List.of("法国"), null, List.of());

        FlightSearchRequest request = FlightSearchParamResolver.resolve(task);

        assertThat(request.queryable()).isTrue();
        assertThat(request.originCode()).isEqualTo("PVG");
        assertThat(request.departureDate()).isEqualTo("2026-10-01");
    }
}
