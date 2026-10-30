package com.cagritasoz.contracts.user;

import lombok.Builder;

// "data" of a UserDeletionCancelled event - empty: the user changed their mind before the deadline, the account
// is ACTIVE again, and consumers lift whatever freeze UserDeletionScheduled put on their writes. Who and when
// are in the envelope (occurredAt is the row's updated_at at the moment of the cancel).
@Builder
public record UserDeletionCancelledData() {
    public static final int SCHEMA_VERSION = 1;
}
