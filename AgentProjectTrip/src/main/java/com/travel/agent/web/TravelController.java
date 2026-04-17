package com.travel.agent.web;

import com.travel.agent.ai.agents.MastermindAgent;
import com.travel.agent.core.dto.FlightDTO;
import com.travel.agent.core.service.FlightService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/v1/travel")
public class TravelController {

    private final FlightService flightService;
    private final MastermindAgent mastermindAgent;

    public TravelController(FlightService flightService, MastermindAgent mastermindAgent) {
        this.flightService = flightService;
        this.mastermindAgent = mastermindAgent;
    }

    @GetMapping("/health")
    public String health() {
        return "ok";
    }

    @GetMapping("/flights")
    public List<FlightDTO> searchFlights(
            @RequestParam(defaultValue = "DUB") String origin,
            @RequestParam(defaultValue = "CDG") String destination,
            @RequestParam(defaultValue = "2024-12-01") String date) {
        return flightService.searchFlights(origin, destination, date);
    }

    @GetMapping("/chat")
    public String chat(@RequestParam String message) {
        return mastermindAgent.chat(message);
    }
}
