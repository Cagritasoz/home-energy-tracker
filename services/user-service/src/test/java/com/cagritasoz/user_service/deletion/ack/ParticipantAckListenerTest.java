package com.cagritasoz.user_service.deletion.ack;

import com.cagritasoz.user_service.consumer.EventEnvelopeReader;
import com.cagritasoz.user_service.consumer.PoisonEventException;
import com.cagritasoz.user_service.consumer.ReceivedEvent;
import com.cagritasoz.user_service.testsupport.data.ContractFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// The listener with the real envelope reader and the real registry, and a mocked service: what it decides about a
// message before any database is involved - which messages are confirmations, which are ignored, which are poison, and
// what is counted. The contract fixture is the message a participant really sends. Against a broker and a database it is
// ParticipantAckConsumerIT.
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ParticipantAckListenerTest {

    private static final String TOPIC = "device.events.v1";

    private static final UUID USER_ID = UUID.fromString("6f9619ff-8b86-4d11-b42d-00c04fc964ff");

    private static final ParticipantAck DEVICES = new ParticipantAck("devices", TOPIC, "UserDevicesUnclaimed", 1);

    @Mock
    private ParticipantAckService service;

    private SimpleMeterRegistry meters;

    private ParticipantAckListener listener;

    private String fixture;

    @BeforeEach
    void createListener() {

        meters = new SimpleMeterRegistry();
        listener = new ParticipantAckListener(new ParticipantAcks(List.of(DEVICES)),
                new EventEnvelopeReader(JsonMapper.builder().build()), service, meters);
        fixture = ContractFixtures.load("UserDevicesUnclaimed", 1).toString();

    }

    private double counted(String outcome) {

        var counter = meters.find("user.deletion.participant.acks")
                .tag("participant", "devices").tag("outcome", outcome).counter();

        return counter == null ? 0 : counter.count();

    }

    @Test
    void handle_confirmation_isRecordedForTheUserInTheAggregateId() {

        when(service.acknowledge(any(), any(), any())).thenReturn(ParticipantAckService.Outcome.ACKED);

        listener.handle(TOPIC, fixture);

        ArgumentCaptor<ReceivedEvent> event = ArgumentCaptor.forClass(ReceivedEvent.class);
        verify(service).acknowledge(event.capture(), org.mockito.ArgumentMatchers.eq(USER_ID), org.mockito.ArgumentMatchers.eq(DEVICES));
        assertThat(event.getValue().eventId()).isEqualTo(UUID.fromString("5b8d2f61-9c07-4e3a-b1d4-7a6c0e9f2d35"));
        assertThat(event.getValue().eventType()).isEqualTo("UserDevicesUnclaimed");

    }

    @Test
    void handle_theRecordArrivesThroughTheKafkaCallback_thatIsTheSamePath() {

        when(service.acknowledge(any(), any(), any())).thenReturn(ParticipantAckService.Outcome.ACKED);

        listener.onRecord(new ConsumerRecord<>(TOPIC, 0, 0L, USER_ID.toString(), fixture));

        verify(service).acknowledge(any(), org.mockito.ArgumentMatchers.eq(USER_ID), org.mockito.ArgumentMatchers.eq(DEVICES));

    }

    @Test
    void handle_everyOutcome_isCountedUnderItsOwnName() {

        when(service.acknowledge(any(), any(), any()))
                .thenReturn(ParticipantAckService.Outcome.ACKED)
                .thenReturn(ParticipantAckService.Outcome.ALREADY_PROCESSED)
                .thenReturn(ParticipantAckService.Outcome.ALREADY_PROCESSED)
                .thenReturn(ParticipantAckService.Outcome.NOT_PENDING);

        for (int i = 0; i < 4; i++) {
            listener.handle(TOPIC, fixture);
        }

        assertThat(counted("acked")).isEqualTo(1);
        assertThat(counted("already_processed")).isEqualTo(2);
        assertThat(counted("not_pending")).isEqualTo(1);

    }

    @Test
    void handle_confirmed_logsTheParticipantAndTheUserIdOnly(CapturedOutput output) {

        when(service.acknowledge(any(), any(), any())).thenReturn(ParticipantAckService.Outcome.ACKED);

        listener.handle(TOPIC, fixture);

        assertThat(output.getOut()).contains("devices").contains(USER_ID.toString());

    }

    // A producer may add event types to a topic this service reads; that is no reason to fail, retry or dead-letter.
    @Test
    void handle_otherEventTypeOnTheTopic_isIgnored() {

        listener.handle(TOPIC, fixture.replace("UserDevicesUnclaimed", "DeviceFirmwareUpdated"));

        verifyNoInteractions(service);
        assertThat(meters.find("user.deletion.participant.acks").counters()).isEmpty();

    }

    // The registry matches the topic as well as the type: the same type name on a topic nobody registered it for is not a confirmation.
    @Test
    void handle_confirmationFromAnotherTopic_isIgnored() {

        listener.handle("somewhere.else.v1", fixture);

        verifyNoInteractions(service);

    }

    // Applying a shape this service has not been written for could misread it: a loud failure, not a silent guess.
    @Test
    void handle_unsupportedSchemaVersion_isPoison() {

        assertThatThrownBy(() -> listener.handle(TOPIC, fixture.replace("\"schemaVersion\":1", "\"schemaVersion\":2")))
                .isInstanceOf(PoisonEventException.class)
                .hasMessageContaining("schemaVersion 2");

        verifyNoInteractions(service);

    }

    // The aggregate of this event is the user; anything else cannot be applied however often it is retried.
    @Test
    void handle_aggregateIdThatIsNotAUserId_isPoison() {

        String value = fixture.replace("6f9619ff-8b86-4d11-b42d-00c04fc964ff", "device-42");

        assertThatThrownBy(() -> listener.handle(TOPIC, value)).isInstanceOf(PoisonEventException.class);

        verifyNoInteractions(service);

    }

    @Test
    void handle_notAnEnvelope_isPoison() {

        assertThatThrownBy(() -> listener.handle(TOPIC, "garbage")).isInstanceOf(PoisonEventException.class);
        assertThatThrownBy(() -> listener.handle(TOPIC, null)).isInstanceOf(PoisonEventException.class);

        verifyNoInteractions(service);

    }

    // The database being down is the error handler's business (retry until it clears): the exception must reach it
    // untouched, and nothing may be counted for a record that was not applied.
    @Test
    void handle_serviceFails_theExceptionPropagatesAndNothingIsCounted() {

        when(service.acknowledge(any(), any(), any())).thenThrow(new DataAccessResourceFailureException("database down"));

        assertThatThrownBy(() -> listener.handle(TOPIC, fixture)).isInstanceOf(DataAccessResourceFailureException.class);

        assertThat(meters.find("user.deletion.participant.acks").counters()).isEmpty();

    }

    @Test
    void handle_poison_neverReachesTheService() {

        try {
            listener.handle(TOPIC, "{");
        }
        catch (PoisonEventException expected) {
            // fine
        }

        verify(service, never()).acknowledge(any(), any(), any());

    }
}
