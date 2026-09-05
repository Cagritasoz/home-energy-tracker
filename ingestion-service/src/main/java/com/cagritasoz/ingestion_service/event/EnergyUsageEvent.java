package com.cagritasoz.ingestion_service.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;

import java.time.Instant;

@Builder
public record EnergyUsageEvent(

        Long deviceId,

        double consumedEnergy,

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant timestamp
) {
}
