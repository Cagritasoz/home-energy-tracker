package com.cagritasoz.contracts.user;

// Fixed values for everything user-service publishes.
public final class UserEvents {

    // ".v1" changes only for a transport-level break (e.g. a different message key), not for payload changes.
    public static final String TOPIC = "user.events.v1";

    public static final String AGGREGATE_TYPE = "User";

    public static final String PRODUCER = "user-service";

    private UserEvents() {
    }
}
