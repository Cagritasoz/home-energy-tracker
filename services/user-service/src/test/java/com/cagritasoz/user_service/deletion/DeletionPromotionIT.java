package com.cagritasoz.user_service.deletion;

import com.cagritasoz.contracts.user.UserEvents;
import com.cagritasoz.user_service.outbox.OutboxService;
import com.cagritasoz.user_service.testsupport.config.ConfigFixtures;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// DeletionPromotionService with the real repositories, the real OutboxService and a real Postgres: what one
// promotion writes, and what it must leave alone. Every test runs in a rolled-back transaction, which also
// satisfies the MANDATORY transaction OutboxService needs. The races (cancel against promote, two promoters) and the
// all-or-nothing behaviour need real commits and are in DeletionPromotionConcurrencyIT, which shares this context.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({PostgresTestContainerConfig.class, OutboxService.class, DeletionPromotionService.class, DeletionSchedulingService.class})
@TestPropertySource(properties = {
        "app.user.deletion.required-participants=devices,usage",
        "app.user.deletion.grace=5m"
})
class DeletionPromotionIT {

    private static final String YEAR_2000 = "2000-01-01T00:00:00Z";

    private static final String FAR_FUTURE = "2999-01-01T00:00:00Z";

    private static final String ORIGINAL_EMAIL = "arthur.morgan@example.com"; // For the rows that are not scheduled.

    private static final String ORIGINAL_NAME = "Arthur Morgan";

    @Autowired
    private DeletionPromotionService promotionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private UserDeletionParticipantRepository participantRepository;

    @Autowired
    private OutboxService outboxService;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void promote_overdueScheduledUser_scrubsTheRowAndWritesTheParticipantsAndTheStartedEventTogether() {

        UUID id = UUID.randomUUID();
        insertScheduledUser(id, YEAR_2000);

        boolean promoted = promotionService.promote(id);

        assertThat(promoted).isTrue();

        Map<String, Object> row = jdbc.queryForMap(
                "SELECT status, email, display_name, version, deletion_started_at FROM users WHERE id = ?", id);
        assertThat(row.get("status")).isEqualTo("DELETING");
        assertThat(row.get("email")).isEqualTo("deleted+" + id + "@invalid");
        assertThat(row.get("display_name")).isEqualTo("Deleted user");
        assertThat(row.get("version")).isEqualTo(1L);

        // The set of the configuration (devices, usage), every row still waiting for its confirmation.
        assertThat(jdbc.queryForList("SELECT participant FROM user_deletion_participants WHERE user_id = ? "
                + "AND acked_at IS NULL AND waived_at IS NULL ORDER BY participant", String.class, id))
                .containsExactly("devices", "usage");

        // Exactly one event, built from the scrubbed row: its time is the row's deletion_started_at, its version
        // the row's, and its payload carries neither the old address nor the old name.
        List<Map<String, Object>> events = jdbc.queryForList(
                "SELECT event_type, topic, aggregate_id, aggregate_version, occurred_at, payload::text AS payload FROM outbox_events");
        assertThat(events).hasSize(1);
        Map<String, Object> event = events.getFirst();
        assertThat(event.get("event_type")).isEqualTo("UserDeletionStarted");
        assertThat(event.get("topic")).isEqualTo(UserEvents.TOPIC);
        assertThat(event.get("aggregate_id")).isEqualTo(id.toString());
        assertThat(event.get("aggregate_version")).isEqualTo(1L);
        assertThat(jdbc.queryForObject("SELECT occurred_at = deletion_started_at FROM outbox_events, users WHERE users.id = ?",
                Boolean.class, id)).isTrue();
        assertThat((String) event.get("payload")).doesNotContain(addressOf(id)).doesNotContain(ORIGINAL_NAME);

    }

    // The grace period is the user's until its last moment.
    @Test
    void promote_userStillInTheGracePeriod_returnsFalseAndWritesNothing() {

        UUID id = UUID.randomUUID();
        insertScheduledUser(id, FAR_FUTURE);

        assertThat(promotionService.promote(id)).isFalse();

        assertThat(statusOf(id)).isEqualTo("DELETION_SCHEDULED");
        assertThat(emailOf(id)).isEqualTo(addressOf(id));
        assertNothingWritten();

    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"ACTIVE", "DELETING", "DELETED"})
    void promote_userNotScheduled_returnsFalseAndWritesNothing(UserStatus status) {

        UUID id = UUID.randomUUID();
        insertUserIn(id, status);

        assertThat(promotionService.promote(id)).isFalse();

        assertThat(statusOf(id)).isEqualTo(status.name());
        assertNothingWritten();

    }

    @Test
    void promote_unknownUser_returnsFalseAndWritesNothing() {

        assertThat(promotionService.promote(UUID.randomUUID())).isFalse();

        assertNothingWritten();

    }

    // A second promoter (or a retry) that arrives after the first committed finds the row DELETING: nothing again.
    @Test
    void promote_calledTwice_promotesOnceWithOneEventAndOneSetOfRows() {

        UUID id = UUID.randomUUID();
        insertScheduledUser(id, YEAR_2000);

        assertThat(promotionService.promote(id)).isTrue();
        assertThat(promotionService.promote(id)).isFalse();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_deletion_participants", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Long.class, id)).isOne();

    }

    // The required set is a snapshot: what is configured at a deletion's promotion becomes its rows, and a later
    // configuration (here a service started with another set) never rewrites those rows.
    @Test
    void promote_configurationChangedAfterAPromotion_leavesTheEarlierRowsAsTheyWere() {

        UUID early = UUID.randomUUID();
        UUID late = UUID.randomUUID();
        insertScheduledUser(early, YEAR_2000);
        insertScheduledUser(late, YEAR_2000);

        promotionService.promote(early);

        DeletionPromotionService afterTheChange =
                new DeletionPromotionService(userRepository, participantRepository, outboxService,
                        ConfigFixtures.deletion("required-participants=alerts"));
        afterTheChange.promote(late);

        assertThat(participantsOf(early)).containsExactly("devices", "usage");
        assertThat(participantsOf(late)).containsExactly("alerts");

    }

    // No participant service exists yet: the deletion starts and is announced, with nobody to wait for.
    @Test
    void promote_emptyRequiredSet_startsTheDeletionWithoutParticipantRows() {

        UUID id = UUID.randomUUID();
        insertScheduledUser(id, YEAR_2000);
        DeletionPromotionService noParticipants =
                new DeletionPromotionService(userRepository, participantRepository, outboxService,
                        ConfigFixtures.deletion("required-participants="));

        assertThat(noParticipants.promote(id)).isTrue();

        assertThat(statusOf(id)).isEqualTo("DELETING");
        assertThat(participantsOf(id)).isEmpty();
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_events", String.class)).containsExactly("UserDeletionStarted");

    }

    // findDue and promote agree: what the list names is promotable, and once promoted it leaves the list.
    @Test
    void findDue_afterPromotion_nolongerListsTheUser() {

        UUID id = UUID.randomUUID();
        insertScheduledUser(id, YEAR_2000);

        assertThat(promotionService.findDue(10)).containsExactly(id);

        promotionService.promote(id);

        assertThat(promotionService.findDue(10)).isEmpty();

    }

    // ---- helpers ------------------------------------------------------------------------------------

    private void assertNothingWritten() {

        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_deletion_participants", Integer.class)).isZero();

    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, id);
    }

    private String emailOf(UUID id) {
        return jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id);
    }

    private List<String> participantsOf(UUID id) {
        return jdbc.queryForList("SELECT participant FROM user_deletion_participants WHERE user_id = ? ORDER BY participant",
                String.class, id);
    }

    // Asked for in the year 2000, due at the given time: YEAR_2000 is long overdue, FAR_FUTURE is still in the grace period.
    private void insertScheduledUser(UUID id, String scheduledFor) {

        jdbc.update("""
                INSERT INTO users (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for, created_at, updated_at)
                VALUES (?, ?, ?, 'DELETION_SCHEDULED', ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::timestamptz)
                """,
                id, addressOf(id), ORIGINAL_NAME, YEAR_2000, scheduledFor, YEAR_2000, YEAR_2000);

    }

    // One address per id: uq_users_email allows no two live accounts with the same one.
    private static String addressOf(UUID id) {
        return "user." + id + "@example.com";
    }

    private void insertUserIn(UUID id, UserStatus status) {

        jdbc.update("""
                INSERT INTO users (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for,
                                   deletion_started_at, deleted_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::timestamptz, ?::timestamptz)
                """,
                id, ORIGINAL_EMAIL, ORIGINAL_NAME, status.name(),
                status == UserStatus.ACTIVE ? null : YEAR_2000,
                status == UserStatus.ACTIVE ? null : YEAR_2000,
                status == UserStatus.DELETING || status == UserStatus.DELETED ? YEAR_2000 : null,
                status == UserStatus.DELETED ? YEAR_2000 : null,
                YEAR_2000, YEAR_2000);

    }
}
