package com.cagritasoz.user_service.deletion;

import com.cagritasoz.contracts.EventHeaders;
import com.cagritasoz.user_service.security.Route;
import com.cagritasoz.user_service.testsupport.containers.KafkaOutage;
import com.cagritasoz.user_service.testsupport.containers.KafkaTestContainerConfig;
import com.cagritasoz.user_service.testsupport.containers.PostgresTestContainerConfig;
import com.cagritasoz.user_service.testsupport.data.ContractFixtures;
import com.cagritasoz.user_service.testsupport.kafka.KafkaTestClient;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.testcontainers.kafka.KafkaContainer;
import tools.jackson.databind.node.ObjectNode;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The whole saga with one participant, `devices`, as the application runs it, with a real Kafka broker: the relay
// publishes user.events.v1, and the test plays device-service by reading UserDeletionStarted from the topic and
// answering it with a UserDevicesUnclaimed built from the contract fixture. Spring's own listener container receives
// the answer, and the finalizer closes the deletion only after it.
//
// What this proves that the pieces' own tests cannot: that the participant row the promoter writes, the confirmation
// the consumer applies and the condition the finalizer waits for are the same thing, end to end.
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
@TestPropertySource(properties = {
        "app.user.deletion.required-participants=devices",
        "app.user.deletion.ack-consumer.enabled=true",
        "app.outbox.relay.interval-ms=200",
        "app.outbox.relay.backoff.initial-ms=500",
        "app.outbox.relay.backoff.max-ms=2000"
})
class DeletionSagaDevicesFlowIT extends DeletionSagaFlowSupport {

    private static final String USER_EVENTS = "user.events.v1";

    private static final String DEVICE_EVENTS = "device.events.v1";

    private static final Duration PATIENCE = Duration.ofSeconds(60);

    @Autowired
    private KafkaContainer kafka;

    private KafkaTestClient client() {

        return new KafkaTestClient(kafka.getBootstrapServers());

    }

    // The deletion waits for the participant however long the quiet period is, and finishes right after its confirmation.
    @Test
    void deletingAccount_waitsForTheDevicesConfirmation_thenFinishes() throws Exception {

        UUID user = UUID.randomUUID();
        keycloakAnswersDeletesWith(204);

        provisionAndRequestDeletion(user);

        // The promoter wrote the participant row together with DELETING, and the Keycloak user is deleted.
        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(100)).until(() -> keycloakDeleted(user));
        assertThat(jdbc.queryForList("SELECT participant FROM user_deletion_participants WHERE user_id = ? AND acked_at IS NULL AND waived_at IS NULL",
                String.class, user)).containsExactly("devices");
        assertThat(pendingDevices()).isEqualTo(1);

        // The quiet period (2 s) passes, and the account still waits: nobody confirmed.
        await().during(Duration.ofSeconds(5)).atMost(Duration.ofSeconds(15)).untilAsserted(() -> {

            assertThat(statusOf(user)).isEqualTo("DELETING");
            assertThat(pendingDevices()).isEqualTo(1);

        });

        // device-service hears UserDeletionStarted from the topic ...
        ConsumerRecord<String, String> started = await().atMost(PATIENCE).pollInterval(Duration.ofMillis(200))
                .until(() -> eventOnTopic(user, "UserDeletionStarted"), event -> event != null);

        // ... and answers it.
        client().send(DEVICE_EVENTS, user.toString(), confirmation(user, header(started, EventHeaders.EVENT_ID)));

        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(100)).until(() -> "DELETED".equals(statusOf(user)));

        assertThat(jdbc.queryForObject("SELECT acked_at IS NOT NULL FROM user_deletion_participants WHERE user_id = ? AND participant = 'devices'",
                Boolean.class, user)).isTrue();
        assertThat(pendingDevices()).isZero();

        // The announcements, as a consumer of user.events.v1 sees them: in order, the last one after the confirmation.
        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(200)).until(() -> typesOnTopic(user).size() == 4);
        assertThat(typesOnTopic(user)).containsExactly(
                "UserRegistered", "UserDeletionScheduled", "UserDeletionStarted", "UserDeletionFinalized");

        // Nothing was dead-lettered on the way.
        assertThat(client().readAll(DEVICE_EVENTS + ".dlt").stream().filter(record -> user.toString().equals(record.key()))).isEmpty();

    }

    // The confirmation arrives while Keycloak is unreachable. It is recorded at once, but the account is finalized only
    // after the Keycloak delete and the quiet period that follows it: both conditions, in either order.
    @Test
    void confirmationBeforeTheKeycloakDelete_stillWaitsForKeycloakAndTheQuietPeriod() throws Exception {

        UUID user = UUID.randomUUID();
        keycloakAnswersDeletesWith(503);

        provisionAndRequestDeletion(user);

        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(100)).until(() -> "DELETING".equals(statusOf(user)));

        client().send(DEVICE_EVENTS, user.toString(), confirmation(user, null));

        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(100)).until(() -> pendingDevices() == 0);

        // Confirmed, but Keycloak has not deleted the user: not finalized.
        await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(15)).untilAsserted(() -> {

            assertThat(statusOf(user)).isEqualTo("DELETING");
            assertThat(keycloakDeleted(user)).isFalse();

        });

        KEYCLOAK.resetMappings();
        tokenEndpointIssuesTokens();
        keycloakAnswersDeletesWith(204);

        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(100)).until(() -> "DELETED".equals(statusOf(user)));

        assertThat(jdbc.queryForObject("SELECT deleted_at >= keycloak_deleted_at + interval '2 seconds' FROM users WHERE id = ?",
                Boolean.class, user)).as("the quiet period runs from the Keycloak delete, not from the confirmation").isTrue();

    }

    // Kafka goes down in the middle of a deletion. The user's requests and every step of the saga that does not need
    // Kafka carry on (the promotion, the Keycloak delete, the quiet period), and the announcements wait in the outbox,
    // unpublished, with the relay failing and backing off. The deletion then waits for device-service, which never
    // heard UserDeletionStarted. When Kafka is back the announcements go out in order, device-service answers, and the
    // account is finished. The broker is paused, not stopped, so it keeps its port.
    @Test
    void kafkaDown_theAnnouncementsWaitInTheOutbox_andTheDeletionFinishesInOrderOnceKafkaIsBack() throws Exception {

        UUID user = UUID.randomUUID();
        keycloakAnswersDeletesWith(204);

        // The relay has published and its producer is connected: what is paused next is a working connection.
        call(Route.ME_GET, user).andExpect(status().isOk());
        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(200)).until(() -> eventOnTopic(user, "UserRegistered") != null);

        KafkaOutage.begin(kafka);

        try {

            // Requests never touch Kafka: the deletion is accepted and the account is still readable.
            call(Route.ME_DELETE, user).andExpect(status().isAccepted());
            call(Route.ME_GET, user).andExpect(status().isOk());

            // The saga's own steps run: promoted (DELETING, devices owed) and its Keycloak user deleted.
            await().atMost(PATIENCE).pollInterval(Duration.ofMillis(100)).until(() -> keycloakDeleted(user));
            assertThat(statusOf(user)).isEqualTo("DELETING");

            // The announcements are in the outbox and nowhere else, and the relay's gauges say so.
            await().atMost(PATIENCE).pollInterval(Duration.ofMillis(200)).untilAsserted(() -> {

                assertThat(unpublishedEventTypes(user)).containsExactly("UserDeletionScheduled", "UserDeletionStarted");
                assertThat(meterRegistry.get("outbox.relay.consecutive.failed.ticks").gauge().value()).isPositive();
                assertThat(meterRegistry.get("outbox.oldest.pending.age.seconds").gauge().value()).isPositive();

            });

            // Past the quiet period, and still not finished: nobody told device-service.
            await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(15)).untilAsserted(() -> {

                assertThat(statusOf(user)).isEqualTo("DELETING");
                assertThat(unpublishedEventTypes(user)).containsExactly("UserDeletionScheduled", "UserDeletionStarted");

            });

        }
        finally {

            KafkaOutage.end(kafka);

        }

        // Kafka is back: the relay catches up, in order. (A record may arrive twice, the outbox promises at-least-once;
        // the order of first appearances is what must hold.)
        ConsumerRecord<String, String> started = await().atMost(PATIENCE).pollInterval(Duration.ofMillis(500))
                .until(() -> eventOnTopic(user, "UserDeletionStarted"), event -> event != null);
        assertThat(distinctTypesOnTopic(user)).containsExactly("UserRegistered", "UserDeletionScheduled", "UserDeletionStarted");
        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(200)).until(() -> unpublishedEventTypes(user).isEmpty());

        // device-service hears it now and answers; the deletion ends, and the last announcement follows.
        client().send(DEVICE_EVENTS, user.toString(), confirmation(user, header(started, EventHeaders.EVENT_ID)));

        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(200)).until(() -> "DELETED".equals(statusOf(user)));
        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(500)).until(() -> distinctTypesOnTopic(user).size() == 4);

        assertThat(distinctTypesOnTopic(user)).containsExactly(
                "UserRegistered", "UserDeletionScheduled", "UserDeletionStarted", "UserDeletionFinalized");
        assertThat(pendingDevices()).isZero();
        assertThat(client().readAll(DEVICE_EVENTS + ".dlt").stream().filter(record -> user.toString().equals(record.key()))).isEmpty();

    }

    // ---- helpers ------------------------------------------------------------------------------------

    // What device-service sends, made out to this user; causationId is the id of the event it answers when known.
    private static String confirmation(UUID user, String causedBy) {

        ObjectNode event = (ObjectNode) ContractFixtures.load("UserDevicesUnclaimed", 1);
        event.put("eventId", UUID.randomUUID().toString());
        event.put("aggregateId", user.toString());

        if (causedBy != null) {

            event.put("causationId", causedBy);

        }

        return event.toString();

    }

    private double pendingDevices() {

        return meterRegistry.get("user.deletion.participant.pending.acks").tag("participant", "devices").gauge().value();

    }

    // The user's records on user.events.v1, in the order the topic holds them (one key, so one partition).
    private List<ConsumerRecord<String, String>> recordsOnTopic(UUID user) {

        return client().readAll(USER_EVENTS).stream().filter(record -> user.toString().equals(record.key())).toList();

    }

    // Each type once, in the order it first appears: what must hold even if the relay delivers a record twice.
    private List<String> distinctTypesOnTopic(UUID user) {

        return typesOnTopic(user).stream().distinct().toList();

    }

    private List<String> unpublishedEventTypes(UUID user) {

        return jdbc.queryForList("SELECT event_type FROM outbox_events WHERE aggregate_id = ? AND published_at IS NULL ORDER BY seq",
                String.class, user.toString());

    }

    private List<String> typesOnTopic(UUID user) {

        return recordsOnTopic(user).stream().map(record -> header(record, EventHeaders.EVENT_TYPE)).toList();

    }

    private ConsumerRecord<String, String> eventOnTopic(UUID user, String eventType) {

        return recordsOnTopic(user).stream()
                .filter(record -> eventType.equals(header(record, EventHeaders.EVENT_TYPE)))
                .findFirst()
                .orElse(null);

    }

    private static String header(ConsumerRecord<String, String> record, String name) {

        Header header = record.headers().lastHeader(name);

        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);

    }
}
