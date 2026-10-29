package com.cagritasoz.user_service.deletion;

import com.cagritasoz.contracts.EventHeaders;
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
        "app.outbox.relay.interval-ms=200"
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
