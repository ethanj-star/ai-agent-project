package com.travel.agent.core.service;

import com.travel.agent.core.dto.FlightDTO;

import java.util.List;
import java.util.Optional;

public interface FlightService {

    Optional<FlightDTO> findById(String id);

    List<FlightDTO> searchFlights(String origin, String destination, String date);
}
