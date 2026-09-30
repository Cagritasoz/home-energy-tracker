package com.cagritasoz.user_service.service;

import com.cagritasoz.contracts.user.UserEventType;
import com.cagritasoz.contracts.user.UserEvents;
import com.cagritasoz.user_service.entity.User;
import com.cagritasoz.user_service.repository.UserRepository;
import com.cagritasoz.user_service.testsupport.PostgresTestContainerConfig;
import com.cagritasoz.user_service.testsupport.UserFixtures;
import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// A sliced application context: only the beans this test needs, not the whole app.
//  - @DataJpaTest starts the persistence slice only (DataSource, Flyway, Hibernate, repositories,
//    transactions) - no controllers, no security, no Kafka. Each test runs in a rolled-back transaction.
//  - replace = NONE keeps the real DataSource instead of an embedded in-memory database.
//  - @ImportAutoConfiguration adds one auto-configuration the slice leaves out: Jackson, so the
//    ObjectMapper injected into OutboxService is configured exactly like the one in the running app.
//  - @Import adds plain beans: the Testcontainers Postgres, and OutboxService itself - @Service
//    classes are not scanned in a JPA slice, so it has to be named explicitly. Created as a real Spring
//    bean, it is wrapped in the transactional proxy, which is what makes MANDATORY testable here.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({PostgresTestContainerConfig.class, OutboxService.class})
class OutboxServiceIntegrationTest {

    private static final String YEAR_2000 = "2000-01-01T00:00:00Z";

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private EntityManager entityManager;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void recordMethods_withoutActiveTransaction_throwIllegalTransactionState() {

        User user = UserFixtures.activeUser().build();

        assertThatThrownBy(() -> outboxService.recordUserRegistered(user))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> outboxService.recordUserUpdated(user))
                .isInstanceOf(IllegalTransactionStateException.class);
        assertThatThrownBy(() -> outboxService.recordUserDeletionRequested(user))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(outboxRowCount(user.getId())).isZero();

    }

    @Test
    void recordUserRegistered_afterProvisioningInsert_writesOneRowStampedWithCreatedAt() {

        UUID id = UUID.randomUUID();

        userRepository.insertIgnoringConflict(id, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME);
        User user = userRepository.findById(id).orElseThrow();

        outboxService.recordUserRegistered(user);
        entityManager.flush();

        Map<String, Object> row = outboxRow(id);

        assertThat(outboxRowCount(id)).isOne();
        assertThat(row)
                .containsEntry("event_type", UserEventType.USER_REGISTERED.value())
                .containsEntry("aggregate_type", UserEvents.AGGREGATE_TYPE)
                .containsEntry("aggregate_version", 0L)
                .containsEntry("topic", UserEvents.TOPIC)
                .containsEntry("published_at", null)
                .containsEntry("attempts", 0)
                .containsEntry("last_error", null)
                .doesNotContainEntry("created_at", null);
        assertThat(row.get("payload_event_id")).isEqualTo(row.get("id").toString());
        assertThat(row.get("payload_email")).isEqualTo(UserFixtures.ARTHUR_MORGAN_EMAIL);
        assertThat(occurredAt(id)).isEqualTo(userTimestamp("created_at", id));
        assertThat(payloadOccurredAt(id)).isEqualTo(occurredAt(id));

    }

    @Test
    void recordUserUpdated_afterSyncEmail_writesRowStampedWithUpdatedAtAndNewVersion() {

        UUID id = UUID.randomUUID();

        insertActiveUserIn2000(id);
        userRepository.syncEmail(id, UserFixtures.LEON_KENNEDY_EMAIL);
        User user = userRepository.findById(id).orElseThrow();

        outboxService.recordUserUpdated(user);
        entityManager.flush();

        Map<String, Object> row = outboxRow(id);

        assertThat(row)
                .containsEntry("event_type", UserEventType.USER_UPDATED.value())
                .containsEntry("aggregate_version", 1L);
        assertThat(row.get("payload_email")).isEqualTo(UserFixtures.LEON_KENNEDY_EMAIL);
        assertThat(occurredAt(id))
                .isEqualTo(userTimestamp("updated_at", id))
                .isAfter(Instant.parse(YEAR_2000)); // Database Trigger works and the occurred_at is equal to updated_at of a user.
        assertThat(payloadOccurredAt(id)).isEqualTo(occurredAt(id));

    }

    @Test
    void recordUserDeletionRequested_afterMarkDeleting_writesRowStampedWithDeletionRequestedAtAndEmptyData() {

        UUID id = UUID.randomUUID();

        insertActiveUserIn2000(id);
        userRepository.markDeleting(id);
        User user = userRepository.findById(id).orElseThrow();

        outboxService.recordUserDeletionRequested(user);
        entityManager.flush();

        Map<String, Object> row = outboxRow(id);

        assertThat(row)
                .containsEntry("event_type", UserEventType.USER_DELETION_REQUESTED.value())
                .containsEntry("aggregate_version", 1L)
                .containsEntry("payload_data", "{}");
        assertThat(occurredAt(id)).isEqualTo(userTimestamp("deletion_requested_at", id));
        assertThat(payloadOccurredAt(id)).isEqualTo(occurredAt(id));

    }

    private void insertActiveUserIn2000(UUID id) {

        jdbc.update("""
                        INSERT INTO users
                        (id, email, display_name, created_at, updated_at)
                        VALUES
                        (?, ?, ?, ?::timestamptz, ?::timestamptz)
                        """,
                id, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, YEAR_2000, YEAR_2000);

    }

    private int outboxRowCount(UUID userId) {

        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT count(*) FROM outbox_events WHERE aggregate_id = ?", Integer.class, userId.toString()));

    }

    private Map<String, Object> outboxRow(UUID userId) {

        return jdbc.queryForMap("""
                        SELECT o.*,
                               o.payload ->> 'eventId'          AS payload_event_id,
                               o.payload -> 'data' ->> 'email'  AS payload_email,
                               (o.payload -> 'data')::text      AS payload_data
                        FROM outbox_events o
                        WHERE o.aggregate_id = ?
                        """,
                userId.toString());

    }

    private Instant occurredAt(UUID userId) {

        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT occurred_at FROM outbox_events WHERE aggregate_id = ?", Instant.class, userId.toString()));

    }

    private Instant payloadOccurredAt(UUID userId) {

        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT (payload ->> 'occurredAt')::timestamptz FROM outbox_events WHERE aggregate_id = ?", Instant.class, userId.toString()));

    }

    private Instant userTimestamp(String column, UUID userId) {

        return Objects.requireNonNull(jdbc.queryForObject(
                "SELECT " + column + " FROM users WHERE id = ?", Instant.class, userId));

    }
}
