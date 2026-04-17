package com.travel.agent.core.dto;

import java.io.Serial;
import java.io.Serializable;

public record FlightDTO(
        String id,
        String origin,
        String destination,
        String airline,
        double priceEuros
) implements Serializable {

    @Serial
    private static final long serialVersionUID = 1L;
}
