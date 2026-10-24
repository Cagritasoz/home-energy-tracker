package com.cagritasoz.user_service.schema;

import com.cagritasoz.user_service.testsupport.PostgresTestContainerConfig;
import com.cagritasoz.user_service.testsupport.UserFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// V13: the services that must confirm a user's data is gone before the deletion can finish. Plain SQL, like
// UserSchemaIT; every rejected case is its own test because a failed statement aborts the transaction.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
class UserDeletionParticipantsSchemaIT {

    private static final String PARTICIPANT = "devices";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void defaults_minimalInsert_isPendingBecauseNeitherTimestampIsSet() {

        UUID userId = insertDeletingUser();

        jdbc.update("INSERT INTO user_deletion_participants (user_id, participant) VALUES (?, ?)", userId, PARTICIPANT);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM user_deletion_participants WHERE user_id = ?", userId);

        assertThat(row)
                .containsEntry("participant", PARTICIPANT)
                .containsEntry("acked_at", null)
                .containsEntry("waived_at", null);

    }

    @ParameterizedTest
    @ValueSource(strings = {"user_id", "participant"})
    void notNullColumns_explicitNull_isRejected(String column) {

        UUID userId = insertDeletingUser();

        assertThatThrownBy(() -> jdbc.update("INSERT INTO user_deletion_participants (user_id, participant) VALUES (?, ?)",
                column.equals("user_id") ? null : userId, column.equals("participant") ? null : PARTICIPANT))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("null value in column \"" + column + "\"");

    }

    // One row per user and participant: this is what the ack consumer relies on when it updates "its" row, and
    // what keeps the promoter from requiring the same participant twice.
    @Test
    void pkUserDeletionParticipants_sameUserAndParticipant_isRejected() {

        UUID userId = insertDeletingUser();
        insertParticipant(userId, PARTICIPANT);

        assertThatThrownBy(() -> insertParticipant(userId, PARTICIPANT))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("pk_user_deletion_participants");

    }

    @Test
    void pkUserDeletionParticipants_sameParticipantForAnotherUser_andAnotherParticipantForTheSameUser_areAccepted() {

        UUID first = insertDeletingUser(UserFixtures.ARTHUR_MORGAN_EMAIL);
        UUID second = insertDeletingUser(UserFixtures.LEON_KENNEDY_EMAIL);

        assertThatCode(() -> {
            insertParticipant(first, PARTICIPANT);
            insertParticipant(second, PARTICIPANT);
            insertParticipant(first, "alerts");
        }).doesNotThrowAnyException();

    }

    @Test
    void fkUserDeletionParticipants_unknownUser_isRejected() {

        assertThatThrownBy(() -> insertParticipant(UUID.randomUUID(), PARTICIPANT))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_user_deletion_participants_user");

    }

    // User rows are soft-deleted and never removed; if one ever is, its participant rows must not be orphaned.
    @Test
    void fkUserDeletionParticipants_deletingTheUserRowWhileParticipantsExist_isRejected() {

        UUID userId = insertDeletingUser();
        insertParticipant(userId, PARTICIPANT);

        assertThatThrownBy(() -> jdbc.update("DELETE FROM users WHERE id = ?", userId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("fk_user_deletion_participants_user");

    }

    // chk_user_deletion_participants_name is: CHECK (participant ~ '^[a-z][a-z0-9_-]{0,62}$'): a lower-case
    // name of at most 63 characters, so a typo cannot become a participant nobody will ever confirm.
    @ParameterizedTest
    @ValueSource(strings = {"devices", "device-service", "alert_rules", "a", "a1"})
    void chkUserDeletionParticipantsName_wellFormedName_isAccepted(String name) {

        UUID userId = insertDeletingUser();

        assertThatCode(() -> insertParticipant(userId, name)).doesNotThrowAnyException();

    }

    @Test
    void chkUserDeletionParticipantsName_longestAllowedName_isAccepted() {

        UUID userId = insertDeletingUser();

        assertThatCode(() -> insertParticipant(userId, "a".repeat(63))).doesNotThrowAnyException();

    }

    @ParameterizedTest
    @ValueSource(strings = {"", "Devices", "devices ", " devices", "1devices", "-devices", "dev ices", "devices\n"})
    void chkUserDeletionParticipantsName_malformedName_isRejected(String name) {

        UUID userId = insertDeletingUser();

        assertThatThrownBy(() -> insertParticipant(userId, name))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_user_deletion_participants_name");

    }

    @Test
    void chkUserDeletionParticipantsName_oneCharacterTooLong_isRejected() {

        UUID userId = insertDeletingUser();

        assertThatThrownBy(() -> insertParticipant(userId, "a".repeat(64)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_user_deletion_participants_name");

    }

    // chk_user_deletion_participants_acked_or_waived is: CHECK (acked_at IS NULL OR waived_at IS NULL): a row was
    // confirmed by the participant or released by a migration, never both, so an audit can tell them apart.
    @Test
    void chkUserDeletionParticipantsAckedOrWaived_ackedOnlyOrWaivedOnly_isAccepted() {

        UUID userId = insertDeletingUser();

        assertThatCode(() -> {
            jdbc.update("INSERT INTO user_deletion_participants (user_id, participant, acked_at) VALUES (?, 'devices', CURRENT_TIMESTAMP)", userId);
            jdbc.update("INSERT INTO user_deletion_participants (user_id, participant, waived_at) VALUES (?, 'alerts', CURRENT_TIMESTAMP)", userId);
        }).doesNotThrowAnyException();

    }

    @Test
    void chkUserDeletionParticipantsAckedOrWaived_both_isRejected() {

        UUID userId = insertDeletingUser();

        assertThatThrownBy(() -> jdbc.update("""
                INSERT INTO user_deletion_participants (user_id, participant, acked_at, waived_at)
                VALUES (?, 'devices', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, userId))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_user_deletion_participants_acked_or_waived");

    }

    // The update the ack consumer will run: it confirms a pending row once, and a redelivery changes nothing
    // (0 rows), which is also the answer for a participant that was never required for this deletion.
    @Test
    void ackUpdate_pendingRow_isConfirmedOnceAndARedeliveryChangesNothing() {

        UUID userId = insertDeletingUser();
        insertParticipant(userId, PARTICIPANT);

        String ack = """
                UPDATE user_deletion_participants SET acked_at = CURRENT_TIMESTAMP
                WHERE user_id = ? AND participant = ? AND acked_at IS NULL
                """;

        assertThat(jdbc.update(ack, userId, PARTICIPANT)).isOne();
        assertThat(jdbc.update(ack, userId, PARTICIPANT)).isZero();
        assertThat(jdbc.update(ack, userId, "alerts")).as("not required for this deletion").isZero();

    }

    // The partial index holds only rows still waiting for their participant, so it stays small, and the
    // watchdog's "who is still missing" reads it.
    @Test
    void index_pendingIsPartialOnRowsNeitherAckedNorWaived() {

        String definition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() AND indexname = 'idx_user_deletion_participants_pending'",
                String.class);

        assertThat(definition)
                .contains("(participant)")
                .contains("acked_at IS NULL")
                .contains("waived_at IS NULL");

    }

    private UUID insertDeletingUser() {

        return insertDeletingUser(UserFixtures.ARTHUR_MORGAN_EMAIL);

    }

    // A participant row needs a user row, and one that is DELETING is what the promoter writes it for.
    private UUID insertDeletingUser(String email) {

        UUID id = UUID.randomUUID();

        jdbc.update("""
                        INSERT INTO users (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for, deletion_started_at)
                        VALUES (?, ?, ?, 'DELETING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """,
                id, email, UserFixtures.ARTHUR_MORGAN_NAME);

        return id;

    }

    private void insertParticipant(UUID userId, String participant) {

        jdbc.update("INSERT INTO user_deletion_participants (user_id, participant) VALUES (?, ?)", userId, participant);

    }
}
