package com.cagritasoz.user_service.outbox.relay;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// app.outbox.* (explained in application.yaml). intervalMs and cron are also read by the @Scheduled annotations, which
// take only a placeholder; they are fields here so that the key is validated too.
@Validated
@ConfigurationProperties(prefix = "app.outbox", ignoreUnknownFields = false)
public record OutboxProperties(@NotNull @Valid Relay relay, @NotNull @Valid Purge purge) {

    public record Relay(@Positive long intervalMs,
                        @Positive int batchSize,
                        @Positive long sendTimeoutMs,
                        @Positive int maxBatchesPerTick,
                        @NotNull @Valid Backoff backoff) {

        public record Backoff(@Positive long initialMs, @Positive long maxMs) {
        }
    }

    public record Purge(@Positive int retentionDays, @Positive int batchSize, @NotBlank String cron) {
    }
}
