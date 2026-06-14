package com.travel.agent.ai.graph.node;

import com.travel.agent.ai.graph.model.BranchTask;
import com.travel.agent.ai.graph.model.BranchTaskType;
import com.travel.agent.ai.graph.model.HotelSearchRequest;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * HotelSearchParamResolver 的单元测试。
 *
 * <p>只验证酒店查询参数推导，不访问真实 SerpApi。</p>
 */
class HotelSearchParamResolverTest {

    /**
     * 目的地、日期和天数齐全时，应推导出入住和退房日期。
     */
    @Test
    void resolveReturnsHotelRequestsForStructuredTask() {
        BranchTask task = new BranchTask("hotel-1", BranchTaskType.HOTEL, "法国意大利酒店",
                List.of("法国", "意大利"), "2026-10-01", List.of(),
                LocalDate.of(2026, 10, 1), 10, "上海", "经济酒店", false);

        List<HotelSearchRequest> requests = HotelSearchParamResolver.resolve(task, 2);

        assertThat(requests).hasSize(2);
        assertThat(requests)
                .extracting(HotelSearchRequest::city)
                .containsExactly("Paris", "Rome");
        assertThat(requests.get(0).checkInDate()).isEqualTo("2026-10-01");
        assertThat(requests.get(0).checkOutDate()).isEqualTo("2026-10-11");
    }

    /**
     * 缺少行程天数时，不应调用酒店工具，因为无法推导退房日期。
     */
    @Test
    void resolveReturnsMissingWhenDurationAbsent() {
        BranchTask task = new BranchTask("hotel-1", BranchTaskType.HOTEL, "法国酒店",
                List.of("法国"), "2026-10-01", List.of(),
                LocalDate.of(2026, 10, 1), null, "上海", "经济酒店", false);

        List<HotelSearchRequest> requests = HotelSearchParamResolver.resolve(task, 2);

        assertThat(requests).hasSize(1);
        assertThat(requests.get(0).queryable()).isFalse();
        assertThat(requests.get(0).missingReason()).contains("行程天数");
    }
}
