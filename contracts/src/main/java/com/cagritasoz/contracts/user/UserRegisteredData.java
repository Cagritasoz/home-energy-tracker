package com.cagritasoz.contracts.user;

import lombok.Builder;

// "data" of a UserRegistered event: the new user's state at registration (a snapshot, so consumers
// can simply upsert). The user id is not repeated here - it is the envelope's aggregateId.
// The status is not included either: a newly registered user is always ACTIVE.
@Builder
public record UserRegisteredData(
        String email,
        String displayName,
        String timezone
) {

    // Shape version of this payload; goes into the envelope's schemaVersion. Bump only for a breaking change.
    public static final int SCHEMA_VERSION = 1;
}
