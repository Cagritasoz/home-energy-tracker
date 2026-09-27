package com.cagritasoz.contracts.user;

// The event types user-service emits. value() is the string that travels on the wire
// (envelope eventType, outbox event_type, Kafka header) - consumers compare against that string.
public enum UserEventType {

    USER_REGISTERED("UserRegistered"),
    USER_UPDATED("UserUpdated"),
    // Emitted when the account moves ACTIVE -> DELETING; not "the user is DELETED".
    USER_DELETION_REQUESTED("UserDeletionRequested");

    private final String value;

    UserEventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
