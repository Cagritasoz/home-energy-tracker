package com.cagritasoz.user_service.consumer;

import tools.jackson.databind.JsonNode;

import java.util.UUID;

// What every consumer needs to read from an event before it knows what the event is about: the envelope's identity
// and routing fields. "data" stays a tree, because only the handler that recognizes the event type knows its shape
// (and an event type this service does not handle is never converted at all).
public record ReceivedEvent(
        UUID eventId,
        String eventType,
        int schemaVersion,
        String aggregateId,
        JsonNode data
) {
}
