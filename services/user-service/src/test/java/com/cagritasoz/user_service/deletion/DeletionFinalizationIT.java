package com.cagritasoz.user_service.deletion;

import com.cagritasoz.contracts.user.UserEvents;
import com.cagritasoz.user_service.outbox.OutboxService;
import com.cagritasoz.user_service.testsupport.containers.PostgresTestContainerConfig;
import com.cagritasoz.user_service.user.UserRepository;
import com.cagritasoz.user_service.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// DeletionFinalizationService with the real repositories, the real OutboxService and a real Postgres: which accounts
// the work list names, what one finalization writes, and what it must leave alone. Every test runs in a rolled-back
// transaction (which also satisfies the MANDATORY transaction OutboxService needs); because CURRENT_TIMESTAMP is the
// start of that transaction, rows seeded "10 minutes ago" are exactly 10 minutes old when the queries run, so the
// quiet-period boundary can be tested exactly.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({PostgresTestContainerConfig.class, OutboxService.class, DeletionFinalizationService.class})
@TestPropertySource(properties = "app.user.deletion.quiet-period=10m")
class DeletionFinalizationIT {

    @Autowired
    private DeletionFinalizationService finalizationService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private JdbcTemplate jdbc;

    // ---- what one finalization writes ---------------------------------------------------------------

    @Test
    void finalizeDeletion_readyAccount_becomesDeletedWithOneFinalizedEvent() {

        UUID id = UUID.randomUUID();
        insertDeletingUser(id, "11 minutes");

        assertThat(finalizationService.finalizeDeletion(id)).isTrue();

        Map<String, Object> row = jdbc.queryForMap("SELECT status, version, deleted_at IS NOT NULL AS has_deleted_at, email FROM users WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("DELETED");
        assertThat(row.get("version")).isEqualTo(1L);
        assertThat(row.get("has_deleted_at")).isEqualTo(true);
        assertThat(row.get("email")).isEqualTo("deleted+" + id + "@invalid"); // stays scrubbed

        List<Map<String, Object>> events = jdbc.queryForList(
                "SELECT event_type, topic, aggregate_id, aggregate_version, payload::text AS payload FROM outbox_events");
        assertThat(events).hasSize(1);
        Map<String, Object> event = events.getFirst();
        assertThat(event.get("event_type")).isEqualTo("UserDeletionFinalized");
        assertThat(event.get("topic")).isEqualTo(UserEvents.TOPIC);
        assertThat(event.get("aggregate_id")).isEqualTo(id.toString());
        assertThat(event.get("aggregate_version")).isEqualTo(1L);

        // The event's time is the row's own deleted_at, not the clock of whoever wrote it.
        assertThat(jdbc.queryForObject("SELECT o.occurred_at = u.deleted_at FROM outbox_events o, users u WHERE u.id = ?",
                Boolean.class, id)).isTrue();

    }

    @Test
    void finalizeDeletion_calledTwice_finalizesOnceWithOneEvent() {

        UUID id = UUID.randomUUID();
        insertDeletingUser(id, "11 minutes");

        assertThat(finalizationService.finalizeDeletion(id)).isTrue();
        assertThat(finalizationService.finalizeDeletion(id)).isFalse();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Long.class, id)).isOne();

    }

    @Test
    void finalizeDeletion_unknownUser_returnsFalseAndWritesNothing() {

        assertThat(finalizationService.finalizeDeletion(UUID.randomUUID())).isFalse();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();

    }

    // ---- the participants ---------------------------------------------------------------------------

    // The saga completes by time alone while no participant service exists.
    @Test
    void ready_noParticipantRows_isReady() {

        UUID id = UUID.randomUUID();
        insertDeletingUser(id, "11 minutes");

        assertThat(finalizationService.findReady(10)).containsExactly(id);

    }

    @Test
    void ready_aParticipantStillPending_isNotReadyAndNothingIsWritten() {

        UUID id = UUID.randomUUID();
        insertDeletingUser(id, "11 minutes");
        insertParticipant(id, "devices", false, false);

        assertThat(finalizationService.findReady(10)).isEmpty();
        assertThat(finalizationService.finalizeDeletion(id)).isFalse();

        assertStillDeleting(id);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();

    }

    @Test
    void ready_everyParticipantConfirmed_isReady() {

        UUID id = UUID.randomUUID();
        insertDeletingUser(id, "11 minutes");
        insertParticipant(id, "devices", true, false);
        insertParticipant(id, "usage", true, false);

        assertThat(finalizationService.findReady(10)).containsExactly(id);
        assertThat(finalizationService.finalizeDeletion(id)).isTrue();

    }

    // A participant retired for good is waived by a migration; its row counts as satisfied.
    @Test
    void ready_aParticipantWaived_isReady() {

        UUID id = UUID.randomUUID();
        insertDeletingUser(id, "11 minutes");
        insertParticipant(id, "devices", true, false);
        insertParticipant(id, "retired", false, true);

        assertThat(finalizationService.findReady(10)).containsExactly(id);
        assertThat(finalizationService.finalizeDeletion(id)).isTrue();

    }

    // One confirmed, one not: all of them must have answered.
    @Test
    void ready_oneConfirmedOnePending_isNotReady() {

        UUID id = UUID.randomUUID();
        insertDeletingUser(id, "11 minutes");
        insertParticipant(id, "devices", true, false);
        insertParticipant(id, "usage", false, false);

        assertThat(finalizationService.findReady(10)).isEmpty();
        assertThat(finalizationService.finalizeDeletion(id)).isFalse();

        assertStillDeleting(id);

    }

    // Another user's pending row is another user's business.
    @Test
    void ready_anotherUsersPendingRow_doesNotBlock() {

        UUID waiting = UUID.randomUUID();
        UUID done = UUID.randomUUID();
        insertDeletingUser(waiting, "11 minutes");
        insertDeletingUser(done, "11 minutes");
        insertParticipant(waiting, "devices", false, false);
        insertParticipant(done, "devices", true, false);

        assertThat(finalizationService.findReady(10)).containsExactly(done);
        assertThat(finalizationService.finalizeDeletion(done)).isTrue();
        assertThat(finalizationService.finalizeDeletion(waiting)).isFalse();

    }

    // ---- the quiet period ---------------------------------------------------------------------------

    @Test
    void ready_quietPeriodNotOverYet_isNotReady() {

        UUID id = UUID.randomUUID();
        insertDeletingUser(id, "9 minutes 59 seconds");

        assertThat(finalizationService.findReady(10)).isEmpty();
        assertThat(finalizationService.finalizeDeletion(id)).isFalse();

        assertStillDeleting(id);

    }

    // <=, not <: the account whose Keycloak user has been gone for exactly the quiet period is ready.
    @Test
    void ready_quietPeriodExactlyOver_isReady() {

        UUID id = UUID.randomUUID();
        insertDeletingUser(id, "10 minutes");

        assertThat(finalizationService.findReady(10)).containsExactly(id);
        assertThat(finalizationService.finalizeDeletion(id)).isTrue();

    }

    // Nothing can be acting for the user only once Keycloak cannot issue tokens any more: no stamp, no finalization.
    @Test
    void ready_keycloakUserNotDeletedYet_isNotReadyHoweverOldTheDeletionIs() {

        UUID id = UUID.randomUUID();
        insertDeletingUser(id, null);

        assertThat(finalizationService.findReady(10)).isEmpty();
        assertThat(finalizationService.finalizeDeletion(id)).isFalse();

        assertStillDeleting(id);

    }

    @Test
    void ready_zeroQuietPeriod_finalizesAnAccountWhoseKeycloakUserWasJustDeleted() {

        UUID id = UUID.randomUUID();
        insertDeletingUser(id, "0 seconds");
        DeletionFinalizationService noWaiting = new DeletionFinalizationService(userRepository, outboxService, Duration.ZERO);

        assertThat(noWaiting.findReady(10)).containsExactly(id);
        assertThat(noWaiting.finalizeDeletion(id)).isTrue();

    }

    // The period is a property of the service, read when it starts; a service started with another one judges by that.
    @Test
    void ready_aLongerQuietPeriod_holdsBackWhatTheShorterOneReleases() {

        UUID id = UUID.randomUUID();
        insertDeletingUser(id, "30 minutes");
        DeletionFinalizationService oneHour = new DeletionFinalizationService(userRepository, outboxService, Duration.ofHours(1));

        assertThat(finalizationService.findReady(10)).containsExactly(id);
        assertThat(oneHour.findReady(10)).isEmpty();
        assertThat(oneHour.finalizeDeletion(id)).isFalse();

    }

    // ---- the other statuses -------------------------------------------------------------------------

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"ACTIVE", "DELETION_SCHEDULED", "DELETED"})
    void ready_accountNotDeleting_isNeitherListedNorFinalized(UserStatus status) {

        UUID id = UUID.randomUUID();
        insertUserIn(id, status);

        assertThat(finalizationService.findReady(10)).isEmpty();
        assertThat(finalizationService.finalizeDeletion(id)).isFalse();

        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, id)).isEqualTo(status.name());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();

    }

    // ---- the work list ------------------------------------------------------------------------------

    @Test
    void findReady_longestGoneFirstAndAtMostTheLimit() {

        UUID newest = UUID.randomUUID();
        UUID oldest = UUID.randomUUID();
        UUID middle = UUID.randomUUID();
        insertDeletingUser(newest, "11 minutes");
        insertDeletingUser(oldest, "3 hours");
        insertDeletingUser(middle, "1 hour");

        assertThat(finalizationService.findReady(10)).containsExactly(oldest, middle, newest);
        assertThat(finalizationService.findReady(2)).containsExactly(oldest, middle);

    }

    @Test
    void findReady_afterFinalization_nolongerListsTheAccount() {

        UUID id = UUID.randomUUID();
        insertDeletingUser(id, "11 minutes");

        finalizationService.finalizeDeletion(id);

        assertThat(finalizationService.findReady(10)).isEmpty();

    }

    // ---- helpers ------------------------------------------------------------------------------------

    private void assertStillDeleting(UUID id) {

        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, id)).isEqualTo("DELETING");
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM users WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Long.class, id)).isZero();

    }

    // A DELETING account as the promoter leaves it, and as the Keycloak job leaves it `keycloakDeletedAgo` ago (null =
    // the Keycloak user has not been deleted yet). Postgres reads the text as an interval ("10 minutes").
    private void insertDeletingUser(UUID id, String keycloakDeletedAgo) {

        jdbc.update("""
                INSERT INTO users (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for,
                                   deletion_started_at, keycloak_deleted_at)
                VALUES (?, ?, 'Deleted user', 'DELETING',
                        CURRENT_TIMESTAMP - interval '5 hours', CURRENT_TIMESTAMP - interval '5 hours',
                        CURRENT_TIMESTAMP - interval '4 hours', CURRENT_TIMESTAMP - CAST(? AS interval))
                """, id, "deleted+" + id + "@invalid", keycloakDeletedAgo);

    }

    private void insertParticipant(UUID userId, String participant, boolean acked, boolean waived) {

        jdbc.update("""
                INSERT INTO user_deletion_participants (user_id, participant, acked_at, waived_at)
                VALUES (?, ?, CASE WHEN ? THEN CURRENT_TIMESTAMP END, CASE WHEN ? THEN CURRENT_TIMESTAMP END)
                """, userId, participant, acked, waived);

    }

    private void insertUserIn(UUID id, UserStatus status) {

        boolean deletingOrDeleted = status == UserStatus.DELETING || status == UserStatus.DELETED;

        jdbc.update("""
                INSERT INTO users (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for,
                                   deletion_started_at, keycloak_deleted_at, deleted_at)
                VALUES (?, ?, 'Someone', ?,
                        CASE WHEN ? THEN CURRENT_TIMESTAMP - interval '5 hours' END,
                        CASE WHEN ? THEN CURRENT_TIMESTAMP - interval '5 hours' END,
                        CASE WHEN ? THEN CURRENT_TIMESTAMP - interval '4 hours' END,
                        CASE WHEN ? THEN CURRENT_TIMESTAMP - interval '3 hours' END,
                        CASE WHEN ? THEN CURRENT_TIMESTAMP - interval '2 hours' END)
                """,
                id, "user." + id + "@example.com", status.name(),
                status != UserStatus.ACTIVE,
                status != UserStatus.ACTIVE,
                deletingOrDeleted,
                deletingOrDeleted,
                status == UserStatus.DELETED);

    }
}
