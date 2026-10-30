package com.cagritasoz.contracts.device;

// The event types device-service emits that other services consume. value() is the string that travels on the wire
// (envelope eventType, outbox event_type, Kafka header) - consumers compare against that string.
public enum DeviceEventType {

    USER_DEVICES_UNCLAIMED("UserDevicesUnclaimed");

    private final String value;

    DeviceEventType(String value) {
        this.value = value;
    }

    public String value() {
        return value;
    }
}
