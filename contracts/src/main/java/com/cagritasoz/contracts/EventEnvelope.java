package com.cagritasoz.contracts;

import lombok.Builder;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

// The outer part shared by every event; T is the event-specific "data" part. This whole object,
// serialized to JSON, is what goes into outbox_events.payload and onto Kafka as the record value.
//
// eventType and aggregateType are plain Strings on purpose, not enums: a consumer must survive a
// producer adding a new event type, and an enum would fail to deserialize a value it doesn't know.
// Producers get compile-time safety from UserEventType and compare via its value().
//
// Required fields mirror the NOT NULL columns of outbox_events. aggregateVersion, correlationId and
// causationId may be null. Timestamps are Instants (written as ISO-8601 by the JSON mapper).
@Builder
public record EventEnvelope<T>(
        UUID eventId,
        String eventType,
        int schemaVersion,
        String aggregateType,
        String aggregateId,
        Long aggregateVersion,
        Instant occurredAt,
        UUID correlationId,
        UUID causationId,
        String producer,
        T data
) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(aggregateType, "aggregateType");
        Objects.requireNonNull(aggregateId, "aggregateId");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(producer, "producer");
        Objects.requireNonNull(data, "data");

        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1, was " + schemaVersion);
        }
    }
}
