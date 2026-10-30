package com.cagritasoz.user_service.consumer;

import com.cagritasoz.user_service.testsupport.data.ContractFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// What a consumer reads from a message before it knows what the event is about, and what it refuses. Every refusal is
// a PoisonEventException: the error handler sends those to the dead-letter topic without retrying, so a message that is
// merely odd must not be one, and a message that can never work must be.
class EventEnvelopeReaderTest {

    private static final String VALID = """
            {"eventId":"5b8d2f61-9c07-4e3a-b1d4-7a6c0e9f2d35","eventType":"UserDevicesUnclaimed","schemaVersion":1,
             "aggregateType":"User","aggregateId":"6f9619ff-8b86-4d11-b42d-00c04fc964ff","producer":"device-service",
             "data":{"devicesUnclaimed":3}}
            """;

    private final EventEnvelopeReader reader = new EventEnvelopeReader(JsonMapper.builder().build());

    @Test
    void read_contractFixture_givesTheEnvelopeFields() {

        ReceivedEvent event = reader.read(ContractFixtures.load("UserDevicesUnclaimed", 1).toString());

        assertThat(event.eventId()).isEqualTo(UUID.fromString("5b8d2f61-9c07-4e3a-b1d4-7a6c0e9f2d35"));
        assertThat(event.eventType()).isEqualTo("UserDevicesUnclaimed");
        assertThat(event.schemaVersion()).isEqualTo(1);
        assertThat(event.aggregateId()).isEqualTo("6f9619ff-8b86-4d11-b42d-00c04fc964ff");
        assertThat(event.data().path("devicesUnclaimed").asInt()).isEqualTo(3);

    }

    // A producer adding a field must not break a consumer.
    @Test
    void read_unknownFields_areIgnored() {

        ReceivedEvent event = reader.read(VALID.replace("\"producer\"", "\"somethingNew\":{\"a\":1},\"producer\""));

        assertThat(event.eventType()).isEqualTo("UserDevicesUnclaimed");

    }

    // An event type this service has never heard of is still a readable event: whether to act on it is the handler's call.
    @Test
    void read_unknownEventType_isReadableLikeAnyOther() {

        ReceivedEvent event = reader.read(VALID.replace("UserDevicesUnclaimed", "SomethingFromTheFuture"));

        assertThat(event.eventType()).isEqualTo("SomethingFromTheFuture");

    }

    @Test
    void read_noData_givesAnEmptyNodeNotAnError() {

        ReceivedEvent event = reader.read("""
                {"eventId":"5b8d2f61-9c07-4e3a-b1d4-7a6c0e9f2d35","eventType":"X","schemaVersion":1,"aggregateId":"a"}
                """);

        assertThat(event.data().isMissingNode()).isTrue();

    }

    @ParameterizedTest
    @NullSource
    @ValueSource(strings = {
            "",
            "not json at all",
            "{\"eventId\":",
            "[]",
            "\"just a string\"",
            "42",
            "null",
            "{}"
    })
    void read_notAnEnvelope_isPoison(String value) {

        assertThatThrownBy(() -> reader.read(value)).isInstanceOf(PoisonEventException.class);

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"eventId\":\"5b8d2f61-9c07-4e3a-b1d4-7a6c0e9f2d35\"",
            "\"eventType\":\"UserDevicesUnclaimed\"",
            "\"aggregateId\":\"6f9619ff-8b86-4d11-b42d-00c04fc964ff\"",
            "\"schemaVersion\":1"
    })
    void read_requiredFieldMissing_isPoison(String field) {

        String without = VALID.replace(field + ",", "").replace("," + field, "");

        assertThat(without).doesNotContain(field);
        assertThatThrownBy(() -> reader.read(without)).isInstanceOf(PoisonEventException.class);

    }

    @ParameterizedTest
    @ValueSource(strings = {
            "\"eventId\":\"5b8d2f61-9c07-4e3a-b1d4-7a6c0e9f2d35\"|\"eventId\":\"not-a-uuid\"",
            "\"eventId\":\"5b8d2f61-9c07-4e3a-b1d4-7a6c0e9f2d35\"|\"eventId\":42",
            "\"eventId\":\"5b8d2f61-9c07-4e3a-b1d4-7a6c0e9f2d35\"|\"eventId\":null",
            "\"eventType\":\"UserDevicesUnclaimed\"|\"eventType\":\"\"",
            "\"eventType\":\"UserDevicesUnclaimed\"|\"eventType\":\"   \"",
            "\"eventType\":\"UserDevicesUnclaimed\"|\"eventType\":7",
            "\"aggregateId\":\"6f9619ff-8b86-4d11-b42d-00c04fc964ff\"|\"aggregateId\":\"\"",
            "\"schemaVersion\":1|\"schemaVersion\":0",
            "\"schemaVersion\":1|\"schemaVersion\":-3",
            "\"schemaVersion\":1|\"schemaVersion\":\"1\"",
            "\"schemaVersion\":1|\"schemaVersion\":1.5",
            "\"schemaVersion\":1|\"schemaVersion\":99999999999999"
    })
    void read_invalidField_isPoison(String replacement) {

        String[] parts = replacement.split("\\|");
        String broken = VALID.replace(parts[0], parts[1]);

        assertThat(broken).isNotEqualTo(VALID);
        assertThatThrownBy(() -> reader.read(broken)).isInstanceOf(PoisonEventException.class);

    }

    // A dead-letter record carries the exception message in a header, and an event can hold an email address.
    @Test
    void read_poison_neverQuotesTheMessage() {

        String secret = "arthur.morgan@example.com";

        assertThatThrownBy(() -> reader.read("{\"eventId\":\"" + secret + "\",\"note\":\"" + secret + "\""))
                .isInstanceOf(PoisonEventException.class)
                .hasMessageNotContaining(secret);
        assertThatThrownBy(() -> reader.read("{\"eventId\":\"" + secret + "\"}"))
                .isInstanceOf(PoisonEventException.class)
                .hasMessageNotContaining(secret);

    }
}
