package com.cagritasoz.contracts.user;

import lombok.Builder;

// "data" of a UserDeletionFinalized event - empty: every required service has confirmed its cleanup and the
// quiet period after the Keycloak delete has passed (occurredAt is the row's deleted_at). Consumers sweep once
// more for anything created by a request that was already in flight when UserDeletionStarted arrived.
@Builder
public record UserDeletionFinalizedData() {
    public static final int SCHEMA_VERSION = 1;
}
