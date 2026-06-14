package com.travel.agent.ai.agents;

import com.travel.agent.ai.graph.model.BranchResult;
import com.travel.agent.ai.graph.model.BranchTask;
import com.travel.agent.ai.graph.model.BranchTaskType;
import com.travel.agent.ai.tools.FlightTools;
import com.travel.agent.ai.tools.KnowledgeTools;
import com.travel.agent.ai.tools.PlacesTools;
import com.travel.agent.ai.tools.WeatherTools;
import com.travel.agent.core.dto.AttractionDTO;
import com.travel.agent.core.dto.FlightDTO;
import com.travel.agent.core.dto.HotelDTO;
import com.travel.agent.core.dto.WeatherDTO;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * BranchAgentFacade 的单元测试。
 *
 * <p>使用 mock 工具验证分支 Agent 门面是否正确路由任务和包装结果，不访问真实外部 API。</p>
 */
class BranchAgentFacadeTest {

    /**
     * 天气任务应调用 WeatherTools，并把 DTO 压缩成 Planner 可读摘要。
     */
    @Test
    void executeWeatherTaskReturnsWeatherSummary() {
        WeatherTools weatherTools = mock(WeatherTools.class);
        PlacesTools placesTools = mock(PlacesTools.class);
        KnowledgeTools knowledgeTools = mock(KnowledgeTools.class);
        BranchAgentFacade facade = new BranchAgentFacade(mock(FlightTools.class), weatherTools, placesTools, knowledgeTools);
        BranchTask task = new BranchTask("weather-1", BranchTaskType.WEATHER, "巴黎天气",
                List.of("Paris"), "下个月", List.of());

        when(weatherTools.getWeather("Paris")).thenReturn(new WeatherDTO("Paris", 18.5, "晴", 55));

        BranchResult result = facade.execute(task);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSummary()).contains("Paris").contains("18.5").contains("晴");
        verify(weatherTools).getWeather("Paris");
    }

    /**
     * 国家级目的地应先转成代表城市，避免把“法国”直接传给 OpenWeatherMap 造成 404。
     */
    @Test
    void executeWeatherTaskNormalizesCountryDestinationsToCities() {
        WeatherTools weatherTools = mock(WeatherTools.class);
        PlacesTools placesTools = mock(PlacesTools.class);
        KnowledgeTools knowledgeTools = mock(KnowledgeTools.class);
        BranchAgentFacade facade = new BranchAgentFacade(mock(FlightTools.class), weatherTools, placesTools, knowledgeTools);
        BranchTask task = new BranchTask("weather-1", BranchTaskType.WEATHER, "法国意大利天气",
                List.of("法国", "意大利"), "国庆", List.of());

        when(weatherTools.getWeather("Paris")).thenReturn(new WeatherDTO("Paris", 18.5, "小雨", 65));
        when(weatherTools.getWeather("Rome")).thenReturn(new WeatherDTO("Rome", 24.0, "晴", 45));

        BranchResult result = facade.execute(task);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSummary()).contains("Paris").contains("Rome").contains("按代表城市查询");
        verify(weatherTools).getWeather("Paris");
        verify(weatherTools).getWeather("Rome");
    }

    /**
     * 未收录的中文目的地不应直接传给天气 API，而应返回分支失败结果。
     */
    @Test
    void executeWeatherTaskFailsWhenDestinationCannotBeNormalized() {
        WeatherTools weatherTools = mock(WeatherTools.class);
        BranchAgentFacade facade = new BranchAgentFacade(
                mock(FlightTools.class),
                weatherTools,
                mock(PlacesTools.class),
                mock(KnowledgeTools.class));
        BranchTask task = new BranchTask("weather-1", BranchTaskType.WEATHER, "天气",
                List.of("神秘目的地"), "国庆", List.of());

        BranchResult result = facade.execute(task);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getSummary()).contains("缺少可查询英文城市");
    }

    /**
     * 景点任务应调用 PlacesTools，并把多个景点合并成短摘要。
     */
    @Test
    void executePlacesTaskReturnsAttractionSummary() {
        WeatherTools weatherTools = mock(WeatherTools.class);
        PlacesTools placesTools = mock(PlacesTools.class);
        KnowledgeTools knowledgeTools = mock(KnowledgeTools.class);
        BranchAgentFacade facade = new BranchAgentFacade(mock(FlightTools.class), weatherTools, placesTools, knowledgeTools);
        BranchTask task = new BranchTask("places-1", BranchTaskType.PLACES, "巴黎景点",
                List.of("Paris"), "下个月", List.of());

        when(placesTools.searchAttractions("Paris")).thenReturn(List.of(
                new AttractionDTO("卢浮宫", 4.7, "博物馆"),
                new AttractionDTO("奥赛博物馆", 4.6, "艺术馆")));

        BranchResult result = facade.execute(task);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSummary()).contains("卢浮宫").contains("奥赛博物馆");
        verify(placesTools).searchAttractions("Paris");
    }

    /**
     * 景点分支同样应把中文城市名规范化为英文城市名。
     */
    @Test
    void executePlacesTaskNormalizesChineseCity() {
        WeatherTools weatherTools = mock(WeatherTools.class);
        PlacesTools placesTools = mock(PlacesTools.class);
        KnowledgeTools knowledgeTools = mock(KnowledgeTools.class);
        BranchAgentFacade facade = new BranchAgentFacade(mock(FlightTools.class), weatherTools, placesTools, knowledgeTools);
        BranchTask task = new BranchTask("places-1", BranchTaskType.PLACES, "巴黎景点",
                List.of("巴黎"), "下个月", List.of());

        when(placesTools.searchAttractions("Paris")).thenReturn(List.of(
                new AttractionDTO("卢浮宫", 4.7, "博物馆")));

        BranchResult result = facade.execute(task);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSummary()).contains("Paris").contains("卢浮宫");
        verify(placesTools).searchAttractions("Paris");
    }

    /**
     * 航班任务在第十二阶段应调用 FlightTools，并把真实候选压缩成摘要。
     */
    @Test
    void executeFlightTaskReturnsFlightSummary() {
        FlightTools flightTools = mock(FlightTools.class);
        BranchAgentFacade facade = new BranchAgentFacade(
                flightTools,
                mock(WeatherTools.class),
                mock(PlacesTools.class),
                mock(KnowledgeTools.class));
        BranchTask task = new BranchTask("flight-1", BranchTaskType.FLIGHT, "巴黎航班",
                List.of("法国"), "2026-10-01", List.of(),
                LocalDate.of(2026, 10, 1), 10, "上海", null, false);

        when(flightTools.searchFlights("PVG", "CDG", "2026-10-01")).thenReturn(List.of(
                new FlightDTO("f1", "PVG", "CDG", "Air France", 620.0)));

        BranchResult result = facade.execute(task);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSummary())
                .contains("航班参考")
                .contains("Air France")
                .contains("620EUR")
                .contains("预算不含国际机票");
        verify(flightTools).searchFlights("PVG", "CDG", "2026-10-01");
    }

    /**
     * 缺少出发地时，航班分支应返回可解释失败结果，而不是调用工具或伪造票价。
     */
    @Test
    void executeFlightTaskFailsWhenDepartureMissing() {
        FlightTools flightTools = mock(FlightTools.class);
        BranchAgentFacade facade = new BranchAgentFacade(
                flightTools,
                mock(WeatherTools.class),
                mock(PlacesTools.class),
                mock(KnowledgeTools.class));
        BranchTask task = new BranchTask("flight-1", BranchTaskType.FLIGHT, "巴黎航班",
                List.of("法国"), "2026-10-01", List.of(),
                LocalDate.of(2026, 10, 1), 10, null, null, true);

        BranchResult result = facade.execute(task);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getSummary()).contains("缺少可识别的出发地");
    }

    /**
     * 酒店任务应调用 PlacesTools.searchHotels，并把价格和评分压缩成 Planner 可读摘要。
     */
    @Test
    void executeHotelTaskReturnsHotelSummary() {
        PlacesTools placesTools = mock(PlacesTools.class);
        BranchAgentFacade facade = new BranchAgentFacade(
                mock(FlightTools.class),
                mock(WeatherTools.class),
                placesTools,
                mock(KnowledgeTools.class));
        BranchTask task = new BranchTask("hotel-1", BranchTaskType.HOTEL, "法国酒店",
                List.of("法国"), "2026-10-01", List.of("经济酒店"),
                LocalDate.of(2026, 10, 1), 10, "上海", "经济酒店", false);

        when(placesTools.searchHotels("Paris", "2026-10-01", "2026-10-11")).thenReturn(List.of(
                new HotelDTO("Hotel Test", "€120", 4.3)));

        BranchResult result = facade.execute(task);

        assertThat(result.isSuccess()).isTrue();
        assertThat(result.getSummary()).contains("酒店参考").contains("Hotel Test").contains("€120");
        verify(placesTools).searchHotels("Paris", "2026-10-01", "2026-10-11");
    }

    /**
     * 工具抛出异常时，分支门面应返回失败结果而不是打断 Graph 主流程。
     */
    @Test
    void executeReturnsFailureWhenToolThrows() {
        WeatherTools weatherTools = mock(WeatherTools.class);
        BranchAgentFacade facade = new BranchAgentFacade(
                mock(FlightTools.class),
                weatherTools,
                mock(PlacesTools.class),
                mock(KnowledgeTools.class));
        BranchTask task = new BranchTask("weather-1", BranchTaskType.WEATHER, "巴黎天气",
                List.of("Paris"), "下个月", List.of());

        when(weatherTools.getWeather("Paris")).thenThrow(new RuntimeException("boom"));

        BranchResult result = facade.execute(task);

        assertThat(result.isSuccess()).isFalse();
        assertThat(result.getSummary()).contains("WEATHER");
        assertThat(result.getErrorMessage()).contains("boom");
    }
}
