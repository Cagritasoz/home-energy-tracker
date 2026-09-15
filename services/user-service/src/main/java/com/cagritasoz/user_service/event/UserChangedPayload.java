package com.cagritasoz.user_service.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;

import java.time.Instant;

// Outbox payload for both USER_CREATED and USER_UPDATED - one shape covers both, since
// OutboxEvent's own eventType column already says which one happened. A second, near-identical
// record differing only in a field name (createdAt vs updatedAt) would just be duplication.
@Builder
public record UserChangedPayload(
        Long userId,
        String firstName,
        String lastName,
        String email,
        String address,

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant occurredAt
) {
}
