package com.cagritasoz.contracts.device;

// Fixed values for everything device-service publishes about users.
public final class DeviceEvents {

    // ".v1" changes only for a transport-level break (e.g. a different message key), not for payload changes.
    public static final String TOPIC = "device.events.v1";

    // The events on this topic that this contract describes are about a user (the key is the user id), not about a
    // device: they are device-service's answers to what user-service announced.
    public static final String AGGREGATE_TYPE = "User";

    public static final String PRODUCER = "device-service";

    private DeviceEvents() {
    }
}
