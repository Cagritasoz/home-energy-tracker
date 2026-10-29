package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.deletion.ack.ParticipantAckMetrics;
import com.cagritasoz.user_service.deletion.ack.ParticipantAcksConfig;
import com.cagritasoz.user_service.testsupport.containers.PostgresTestContainerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

// The watchdog gauges against real rows: what they count and what they measure. Rolled-back transactions, in which
// CURRENT_TIMESTAMP is fixed, so a row seeded "2 hours ago" is exactly 7200 seconds old when the gauge reads it.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestContainerConfig.class, DeletionMetrics.class, ParticipantAckMetrics.class, ParticipantAcksConfig.class})
class DeletionMetricsIT {

    @Autowired
    private DeletionMetrics deletionMetrics;

    @Autowired
    private ParticipantAckMetrics participantAckMetrics;

    @Autowired
    private JdbcTemplate jdbc;

    private SimpleMeterRegistry meters;

    @BeforeEach
    void bind() {

        meters = new SimpleMeterRegistry();
        deletionMetrics.bindTo(meters);
        participantAckMetrics.bindTo(meters);

    }

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }

    private double pending(String participant) {
        return meters.get("user.deletion.participant.pending.acks").tag("participant", participant).gauge().value();
    }

    @Test
    void emptyDatabase_everythingReadsZero() {

        assertThat(gauge("user.deletion.scheduled.accounts")).isZero();
        assertThat(gauge("user.deletion.deleting.oldest.age.seconds")).isZero();
        assertThat(gauge("user.deletion.keycloak.oldest.pending.age.seconds")).isZero();
        assertThat(pending("devices")).isZero();

    }

    @Test
    void scheduledAccounts_countsOnlyTheScheduled() {

        insertUser(UUID.randomUUID(), "DELETION_SCHEDULED", null, null);
        insertUser(UUID.randomUUID(), "DELETION_SCHEDULED", null, null);
        insertUser(UUID.randomUUID(), "ACTIVE", null, null);
        insertUser(UUID.randomUUID(), "DELETING", "1 hour", null);

        assertThat(gauge("user.deletion.scheduled.accounts")).isEqualTo(2);

    }

    // The longest-running deletion, and only DELETING ones.
    @Test
    void oldestDeletingAge_isTheLongestRunningDeletionInSeconds() {

        insertUser(UUID.randomUUID(), "DELETING", "10 minutes", null);
        insertUser(UUID.randomUUID(), "DELETING", "2 hours", "1 hour");
        insertUser(UUID.randomUUID(), "DELETED", "5 days", "5 days");
        insertUser(UUID.randomUUID(), "ACTIVE", null, null);

        assertThat(gauge("user.deletion.deleting.oldest.age.seconds")).isCloseTo(7200, within(1.0));

    }

    // The Keycloak gauge looks at accounts still waiting for their Keycloak delete, not at finished ones.
    @Test
    void oldestPendingKeycloakAge_ignoresAccountsWhoseKeycloakUserIsGone() {

        insertUser(UUID.randomUUID(), "DELETING", "3 hours", "2 hours"); // done: not counted, however old
        insertUser(UUID.randomUUID(), "DELETING", "30 minutes", null);
        insertUser(UUID.randomUUID(), "DELETING", "10 minutes", null);

        assertThat(gauge("user.deletion.keycloak.oldest.pending.age.seconds")).isCloseTo(1800, within(1.0));

    }

    @Test
    void oldestPendingKeycloakAge_everyKeycloakUserDeleted_readsZero() {

        insertUser(UUID.randomUUID(), "DELETING", "3 hours", "2 hours");

        assertThat(gauge("user.deletion.keycloak.oldest.pending.age.seconds")).isZero();

    }

    // Pending means neither confirmed nor waived, counted per participant.
    @Test
    void pendingAcks_countsUnsatisfiedRowsOfThatParticipantOnly() {

        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();
        UUID c = UUID.randomUUID();
        insertUser(a, "DELETING", "1 hour", null);
        insertUser(b, "DELETING", "1 hour", null);
        insertUser(c, "DELETING", "1 hour", null);
        insertParticipant(a, "devices", false, false); // pending
        insertParticipant(b, "devices", false, false); // pending
        insertParticipant(c, "devices", true, false);  // confirmed
        insertParticipant(a, "usage", false, true);    // waived
        insertParticipant(b, "usage", false, false);   // pending, but "usage" has no handler yet: no gauge asks about it

        assertThat(pending("devices")).isEqualTo(2);

    }

    // ---- helpers ------------------------------------------------------------------------------------

    // `startedAgo`: how long ago the deletion started (DELETING and DELETED rows); `keycloakDeletedAgo`: when the
    // Keycloak user was deleted, null if not yet. Both are Postgres intervals as text ("2 hours").
    private void insertUser(UUID id, String status, String startedAgo, String keycloakDeletedAgo) {

        boolean scheduledOrLater = !status.equals("ACTIVE");
        boolean started = status.equals("DELETING") || status.equals("DELETED");

        jdbc.update("""
                INSERT INTO users (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for,
                                   deletion_started_at, keycloak_deleted_at, deleted_at)
                VALUES (?, ?, 'Someone', ?,
                        CASE WHEN ? THEN CURRENT_TIMESTAMP - interval '10 days' END,
                        CASE WHEN ? THEN CURRENT_TIMESTAMP - interval '10 days' END,
                        CASE WHEN ? THEN CURRENT_TIMESTAMP - CAST(? AS interval) END,
                        CASE WHEN ? THEN CURRENT_TIMESTAMP - CAST(? AS interval) END,
                        CASE WHEN ? THEN CURRENT_TIMESTAMP END)
                """,
                id, "user." + id + "@example.com", status,
                scheduledOrLater, scheduledOrLater,
                started, started ? startedAgo : "0 seconds",
                keycloakDeletedAgo != null, keycloakDeletedAgo != null ? keycloakDeletedAgo : "0 seconds",
                status.equals("DELETED"));

    }

    private void insertParticipant(UUID userId, String participant, boolean acked, boolean waived) {

        jdbc.update("""
                INSERT INTO user_deletion_participants (user_id, participant, acked_at, waived_at)
                VALUES (?, ?, CASE WHEN ? THEN CURRENT_TIMESTAMP END, CASE WHEN ? THEN CURRENT_TIMESTAMP END)
                """, userId, participant, acked, waived);

    }
}
