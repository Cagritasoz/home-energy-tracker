package com.cagritasoz.usage_service.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;

import java.time.Instant;

// Independent copy of ingestion-service's producer-side record, deliberately not shared via a
// common module - see the spring.json.type.mapping alias in application.properties for why.
@Builder
public record EnergyUsageEvent(

        Long deviceId,

        double consumedEnergy,

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant timestamp
) {
}
