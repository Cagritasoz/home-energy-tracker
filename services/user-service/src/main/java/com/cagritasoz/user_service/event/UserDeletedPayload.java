package com.cagritasoz.user_service.event;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;

import java.time.Instant;

// Deliberately minimal - a consumer reacting to a delete (e.g. device-service removing this
// user's devices) only ever needs the id, not a snapshot of fields that no longer matter.
@Builder
public record UserDeletedPayload(
        Long userId,

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant occurredAt
) {
}
