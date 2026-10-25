package com.cagritasoz.user_service.model;

// The account's place in the deletion saga. ACTIVE -> DELETION_SCHEDULED (the user asked, the grace period runs,
// still cancellable) -> DELETING (irreversible, cleanup in progress) -> DELETED (finished, a scrubbed tombstone).
// DELETION_SCHEDULED -> ACTIVE is the cancel.
public enum UserStatus {
    ACTIVE,
    DELETION_SCHEDULED,
    DELETING,
    DELETED;

    // The account no longer exists as far as the API is concerned: every endpoint answers 410 Gone. A scheduled
    // account is not gone: it can still be read and cancelled.
    public boolean isGone() {
        return this == DELETING || this == DELETED;
    }
}
