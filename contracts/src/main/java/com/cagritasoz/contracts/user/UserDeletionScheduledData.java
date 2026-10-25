package com.cagritasoz.contracts.user;

import lombok.Builder;

import java.time.Instant;

// "data" of a UserDeletionScheduled event: the user asked to be deleted and the grace period is running.
// deletionScheduledFor is the absolute time the deletion becomes irreversible (the envelope's occurredAt is
// when the user asked). Consumers use the event to freeze their sensitive writes for the user and, in
// notification-service's case, to tell the user with a cancel link; nothing has been deleted yet, so there is
// no reason to carry any user field.
@Builder
public record UserDeletionScheduledData(
        Instant deletionScheduledFor
) {
    public static final int SCHEMA_VERSION = 1;
}
