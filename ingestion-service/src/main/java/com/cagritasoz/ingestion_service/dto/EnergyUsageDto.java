package com.cagritasoz.ingestion_service.dto;


import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;

import java.time.Instant;

@Builder
@JsonPropertyOrder({"deviceId", "consumedEnergy", "timestamp"})
public record EnergyUsageDto (

        Long deviceId,

        double consumedEnergy,

        // Forces ISO-8601 string output (e.g. "2026-09-03T15:04:51Z") instead of a numeric
        // epoch value. Jackson 3 already defaults Instant to string form on its own, so this
        // is currently redundant, but it pins the contract explicitly rather than relying on
        // that default holding forever.
        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant timestamp
) {


}
