package com.travel.agent.ai.tools;

import com.travel.agent.core.dto.FlightDTO;
import com.travel.agent.core.service.FlightService;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class FlightTools {

    private final FlightService flightService;

    public FlightTools(FlightService flightService) {
        this.flightService = flightService;
    }

    @Tool(description = "用于查询从出发地到目的地的航班机票信息。必须包含出发地三字码、目的地三字码和日期(YYYY-MM-DD)")
    public List<FlightDTO> searchFlights(String origin, String destination, String date) {
        return flightService.searchFlights(origin, destination, date);
    }
}
