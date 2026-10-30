package com.cagritasoz.user_service.deletion.ack;

import com.cagritasoz.user_service.consumer.EventEnvelopeReader;
import com.cagritasoz.user_service.consumer.PoisonEventException;
import com.cagritasoz.user_service.testsupport.containers.KafkaTestContainerConfig;
import com.cagritasoz.user_service.testsupport.containers.PostgresTestContainerConfig;
import com.cagritasoz.user_service.testsupport.data.ContractFixtures;
import com.cagritasoz.user_service.testsupport.kafka.KafkaTestClient;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeast;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

// The participant-ack consumer as the application runs it: a real Kafka broker, a real Postgres, Spring's own listener
// container and error handler, and messages produced by hand the way device-service will produce them. Nothing is
// called directly; every assertion is about what the consumer did to the database and to the dead-letter topic.
//
// The context is closed after the class: a cached context would keep a consumer running against the shared broker.
@SpringBootTest(properties = {
        "app.user.deletion.ack-consumer.enabled=true",
        "app.user.deletion.required-participants=devices",
        "app.outbox.relay.interval-ms=86400000",
        "app.outbox.purge.cron=-",
        "app.user.deletion.promoter.interval-ms=86400000",
        "app.user.deletion.keycloak.interval-ms=86400000",
        "app.keycloak.admin.client-secret=consumer-test-secret"
})
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ParticipantAckConsumerIT {

    private static final String TOPIC = "device.events.v1";

    private static final String DEAD_LETTER_TOPIC = "device.events.v1.dlt";

    private static final Duration PATIENCE = Duration.ofSeconds(60);

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private KafkaContainer kafka;

    @MockitoSpyBean
    private ParticipantAckService ackService;

    // Spied only to count how often a message is read: a record that is retried is read again.
    @MockitoSpyBean
    private EventEnvelopeReader reader;

    private KafkaTestClient client;

    @BeforeEach
    void connectAndEmptyTables() {

        client = new KafkaTestClient(kafka.getBootstrapServers());
        jdbc.execute("TRUNCATE TABLE processed_events, user_deletion_participants, outbox_events, users RESTART IDENTITY CASCADE");

    }

    // ---- the happy path -----------------------------------------------------------------------------

    @Test
    void confirmation_marksTheParticipantRowAndIsRecordedAsProcessed() {

        UUID user = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        insertDeletingUserWaitingFor(user, "devices", "usage");

        client.send(TOPIC, user.toString(), confirmation(eventId, user));

        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(100)).until(() -> acked(user, "devices"));

        assertThat(acked(user, "usage")).as("another participant's row is not touched").isFalse();
        assertThat(jdbc.queryForList("SELECT handler FROM processed_events WHERE event_id = ?", String.class, eventId))
                .containsExactly("participant-ack:devices");
        assertThat(deadLettersFor(user)).isEmpty();

    }

    // At-least-once: a copy of the same event (a retried producer, a replay) is applied once. Proof that the second copy
    // was seen and skipped, not lost: it is counted, and the processed row is still the only one.
    @Test
    void sameEventDeliveredTwice_isAppliedOnce() {

        UUID user = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        insertDeletingUserWaitingFor(user, "devices");
        double duplicatesBefore = counted("already_processed");

        client.send(TOPIC, user.toString(), confirmation(eventId, user));
        client.send(TOPIC, user.toString(), confirmation(eventId, user));

        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(100))
                .until(() -> counted("already_processed") == duplicatesBefore + 1);

        assertThat(acked(user, "devices")).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, eventId)).isOne();
        assertThat(deadLettersFor(user)).isEmpty();

    }

    // A confirmation for a deletion that does not wait for this participant: counted, ignored, not an error.
    @Test
    void confirmationNobodyWaitsFor_isIgnoredWithoutADeadLetter() {

        UUID stranger = UUID.randomUUID();
        double ignoredBefore = counted("not_pending");

        client.send(TOPIC, stranger.toString(), confirmation(UUID.randomUUID(), stranger));

        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(100)).until(() -> counted("not_pending") == ignoredBefore + 1);

        assertThat(deadLettersFor(stranger)).isEmpty();

    }

    // A producer adding an event type to the topic must not hurt this consumer.
    @Test
    void otherEventTypeOnTheTopic_isIgnoredAndTheNextConfirmationStillWorks() {

        UUID user = UUID.randomUUID();
        insertDeletingUserWaitingFor(user, "devices");

        client.send(TOPIC, user.toString(), confirmation(UUID.randomUUID(), user).replace("UserDevicesUnclaimed", "DeviceFirmwareUpdated"));
        client.send(TOPIC, user.toString(), confirmation(UUID.randomUUID(), user));

        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(100)).until(() -> acked(user, "devices"));

        assertThat(deadLettersFor(user)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class)).as("the unknown type was not claimed").isOne();

    }

    // ---- poison goes to the dead-letter topic -------------------------------------------------------

    // Each kind of message that can never be applied ends up, unchanged, on <topic>.dlt with the reason in a header -
    // and the consumer is not stuck behind it: a valid confirmation sent after the last poison message is applied.
    @Test
    void poisonMessages_goToTheDeadLetterTopicUnchanged_andDoNotBlockTheRecordsBehindThem() {

        UUID user = UUID.randomUUID();
        insertDeletingUserWaitingFor(user, "devices");

        String notJson = "this is not json " + user;
        String wrongVersion = confirmation(UUID.randomUUID(), user).replace("\"schemaVersion\":1", "\"schemaVersion\":7");
        String notAUserId = confirmation(UUID.randomUUID(), user).replace(user.toString(), "device-42");

        client.send(TOPIC, user.toString(), notJson);
        client.send(TOPIC, user.toString(), wrongVersion);
        client.send(TOPIC, user.toString(), notAUserId);
        client.send(TOPIC, user.toString(), confirmation(UUID.randomUUID(), user));

        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(100)).until(() -> acked(user, "devices"));
        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(200)).until(() -> deadLettersFor(user).size() == 3);

        List<ConsumerRecord<String, String>> deadLetters = deadLettersFor(user);

        assertThat(deadLetters).extracting(ConsumerRecord::value).containsExactlyInAnyOrder(notJson, wrongVersion, notAUserId);
        assertThat(deadLetters).allSatisfy(record -> {

            assertThat(record.key()).isEqualTo(user.toString());
            assertThat(header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC)).isEqualTo(TOPIC);
            // Spring Kafka wraps the listener's exception; the exception the listener threw is the cause.
            assertThat(header(record, KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN)).isEqualTo(PoisonEventException.class.getName());

        });

        // The reason is in the header, and the header never repeats the message's content.
        ConsumerRecord<String, String> badVersion = deadLetters.stream().filter(r -> r.value().equals(wrongVersion)).findFirst().orElseThrow();
        assertThat(header(badVersion, KafkaHeaders.DLT_EXCEPTION_MESSAGE)).contains("schemaVersion 7");

        // None of the three was claimed: only the one valid confirmation was applied.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class)).isOne();

        // Poison is never retried: each of the three was read exactly once (a retry would read it again).
        verify(reader, times(1)).read(notJson);
        verify(reader, times(1)).read(wrongVersion);
        verify(reader, times(1)).read(notAUserId);

    }

    // ---- the database being down is not the record's fault ------------------------------------------

    // The consumer retries a record for as long as the database fails and then applies it. The record is never
    // dead-lettered: skipping it would lose a confirmation, and the deletion would wait for it forever.
    //
    // Four failures on purpose: that is more than the three retries any other failure gets before the dead-letter
    // topic, so only the "the database is the problem" policy gets this record applied. (The pauses are 1, 2, 4 and 8
    // seconds, so the test takes about fifteen.)
    @Test
    void databaseFailure_isRetriedUntilItClears_andNeverDeadLettered() {

        UUID user = UUID.randomUUID();
        insertDeletingUserWaitingFor(user, "devices");

        doThrow(new DataAccessResourceFailureException("database down"))
                .doThrow(new DataAccessResourceFailureException("database still down"))
                .doThrow(new DataAccessResourceFailureException("database still down"))
                .doThrow(new DataAccessResourceFailureException("database still down"))
                .doCallRealMethod()
                .when(ackService).acknowledge(any(), any(), any());

        client.send(TOPIC, user.toString(), confirmation(UUID.randomUUID(), user));

        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(100)).until(() -> acked(user, "devices"));

        assertThat(deadLettersFor(user)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class)).isOne();
        verify(ackService, atLeast(5)).acknowledge(any(), any(), any());

    }

    // ---- helpers ------------------------------------------------------------------------------------

    // The contract fixture, made out to this user and this event id: what device-service sends.
    private static String confirmation(UUID eventId, UUID userId) {

        ObjectNode event = (ObjectNode) ContractFixtures.load("UserDevicesUnclaimed", 1);
        event.put("eventId", eventId.toString());
        event.put("aggregateId", userId.toString());

        return event.toString();

    }

    private double counted(String outcome) {

        var counter = meterRegistry.find("user.deletion.participant.acks")
                .tag("participant", "devices").tag("outcome", outcome).counter();

        return counter == null ? 0 : counter.count();

    }

    private boolean acked(UUID user, String participant) {

        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT acked_at IS NOT NULL FROM user_deletion_participants WHERE user_id = ? AND participant = ?",
                Boolean.class, user, participant));

    }

    // What is on the dead-letter topic for this user (every test uses its own user ids, so tests do not see each other's).
    private List<ConsumerRecord<String, String>> deadLettersFor(UUID user) {

        return client.readAll(DEAD_LETTER_TOPIC).stream().filter(record -> user.toString().equals(record.key())).toList();

    }

    private static String header(ConsumerRecord<String, String> record, String name) {

        Header header = record.headers().lastHeader(name);

        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);

    }

    private void insertDeletingUserWaitingFor(UUID id, String... participants) {

        jdbc.update("""
                INSERT INTO users (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for, deletion_started_at)
                VALUES (?, ?, 'Deleted user', 'DELETING',
                        CURRENT_TIMESTAMP - interval '5 hours', CURRENT_TIMESTAMP - interval '5 hours', CURRENT_TIMESTAMP - interval '4 hours')
                """, id, "deleted+" + id + "@invalid");

        for (String participant : participants) {

            jdbc.update("INSERT INTO user_deletion_participants (user_id, participant) VALUES (?, ?)", id, participant);

        }

    }
}
