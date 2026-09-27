package com.cagritasoz.contracts;

// Names of the Kafka record headers the outbox relay sets, so producers and consumers can't
// disagree on the spelling. The message KEY is the envelope's aggregateId.
public final class EventHeaders {

    public static final String EVENT_ID = "event_id";
    public static final String EVENT_TYPE = "event_type";
    public static final String SCHEMA_VERSION = "schema_version";

    private EventHeaders() {
    }
}
