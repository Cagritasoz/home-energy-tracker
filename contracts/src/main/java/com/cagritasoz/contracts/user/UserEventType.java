package com.cagritasoz.contracts.user;

// The event types user-service emits. value() is the string that travels on the wire
// (envelope eventType, outbox event_type, Kafka header) - consumers compare against that string.
public enum UserEventType {

    USER_REGISTERED("UserRegistered"),
    USER_UPDATED("UserUpdated"),
    USER_DELETION_SCHEDULED("UserDeletionScheduled"),
    USER_DELETION_CANCELLED("UserDeletionCancelled"),
    USER_DELETION_STARTED("UserDeletionStarted"),
    USER_DELETION_FINALIZED("UserDeletionFinalized");

    private final String value;

    UserEventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
