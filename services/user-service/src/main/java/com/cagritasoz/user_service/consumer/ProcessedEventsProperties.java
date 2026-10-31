package com.cagritasoz.user_service.consumer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// app.processed-events.* (explained in application.yaml). cron is also read by the @Scheduled annotation, which takes
// only a placeholder; it is a field here so that the key is validated too.
@Validated
@ConfigurationProperties(prefix = "app.processed-events", ignoreUnknownFields = false)
public record ProcessedEventsProperties(@NotNull @Valid Purge purge) {

    public record Purge(@Positive int retentionDays, @Positive int batchSize, @NotBlank String cron) {
    }
}
