package com.cagritasoz.user_service.consumer;

import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

// Turns a Kafka record value into a ReceivedEvent. The value is read as a plain string by the consumer (a string
// cannot fail to deserialize), so everything that can be wrong with a message is found here, as an ordinary
// exception: a PoisonEventException, which the error handler sends to the dead-letter topic without retrying.
//
// Reading the envelope as a tree, not into a class, is what lets this service survive its producers event integrity changing:
// unknown fields are never a problem, and an event type added tomorrow is just a string it does not recognize.
@Component
public class EventEnvelopeReader {

    private final ObjectMapper objectMapper;

    public EventEnvelopeReader(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public ReceivedEvent read(String value) {

        if (value == null) {

            throw new PoisonEventException("The record has no value");

        }

        JsonNode root;

        try {

            root = objectMapper.readTree(value);

        }
        catch (JacksonException e) {

            // The parser's message can quote the text it choked on, so it is not passed on.
            throw new PoisonEventException("The record value is not JSON");

        }

        if (!root.isObject()) {

            throw new PoisonEventException("The record value is not a JSON object");

        }

        UUID eventId = uuid(root);
        String eventType = text(root, "eventType");
        String aggregateId = text(root, "aggregateId");

        JsonNode version = root.path("schemaVersion");

        if (!version.isIntegralNumber() || !version.canConvertToInt() || version.asInt() < 1) {

            throw new PoisonEventException("The event has no valid schemaVersion");

        }

        return new ReceivedEvent(eventId, eventType, version.asInt(), aggregateId, root.path("data"));

    }

    private static String text(JsonNode root, String field) {

        JsonNode node = root.path(field);

        if (!node.isString() || node.asString().isBlank()) {

            throw new PoisonEventException("The event has no valid " + field);

        }

        return node.asString();

    }

    private static UUID uuid(JsonNode root) {

        try {

            return UUID.fromString(text(root, "eventId"));

        }
        catch (IllegalArgumentException e) {

            throw new PoisonEventException("The event has no valid " + "eventId");

        }

    }
}
