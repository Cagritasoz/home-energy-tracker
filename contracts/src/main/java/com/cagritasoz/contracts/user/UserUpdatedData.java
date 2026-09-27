package com.cagritasoz.contracts.user;

import lombok.Builder;

// "data" of a UserUpdated event: the user's FULL state after the change (a snapshot, not just the
// fields that changed), so a consumer can upsert it without knowing what the previous state was.
// Consumers reject a snapshot older than the one they hold by comparing the envelope's aggregateVersion.
// Same fields as UserRegisteredData today, but a separate record on purpose: the two events can
// evolve independently.
@Builder
public record UserUpdatedData(
        String email,
        String displayName,
        String timezone
) {

    // Shape version of this payload; goes into the envelope's schemaVersion. Bump only for a breaking change.
    public static final int SCHEMA_VERSION = 1;
}
