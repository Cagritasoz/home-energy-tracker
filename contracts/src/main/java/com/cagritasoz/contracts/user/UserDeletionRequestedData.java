package com.cagritasoz.contracts.user;

import lombok.Builder;

// "data" of a UserDeletionRequested event - deliberately empty. Unlike UserRegistered/UserUpdated,
// this isn't a state snapshot: the envelope alone already has everything a consumer needs - who
// (aggregateId) and when (occurredAt, copied from the row's own deletion_requested_at). A consumer
// building a tombstone table (e.g. user_tombstones(user_id, deleted_at)) reads those two envelope
// fields directly. No user fields (email, displayName, ...) are carried here on purpose: this event
// exists to make consumers stop holding the user's data, so it must not hand them a fresh copy of it.
@Builder
public record UserDeletionRequestedData() {

    // Shape version of this payload; goes into the envelope's schemaVersion. Bump only for a breaking change.
    public static final int SCHEMA_VERSION = 1;
}
