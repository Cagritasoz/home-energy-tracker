package com.cagritasoz.user_service.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;

import java.time.Instant;

// Deliberately minimal, same reasoning as UserDeletedPayload. userId is included even though it's
// also the outbox row's partitionKey - a consumer reading only the Kafka message body (not the
// outbox's own bookkeeping columns) still needs to know which user this rule belonged to.
@Builder
public record AlertRuleDeletedPayload(
        Long ruleId,
        Long userId,

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant occurredAt
) {
}
