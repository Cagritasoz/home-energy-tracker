package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.testsupport.containers.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The promoter's insert into user_deletion_participants, against a real Postgres. The table's own rules (primary
// key, foreign key, name format, acked-or-waived) are proven in plain SQL by UserDeletionParticipantsSchemaIT; this
// class covers what the repository method does on top of them.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
class UserDeletionParticipantRepositoryIT {

    @Autowired
    private UserDeletionParticipantRepository participantRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void insertRequired_newParticipant_insertsAPendingRow() {

        UUID userId = insertDeletingUser();

        int inserted = participantRepository.insertRequired(userId, "devices");

        assertThat(inserted).isOne();
        assertThat(jdbc.queryForObject("""
                SELECT acked_at IS NULL AND waived_at IS NULL
                FROM user_deletion_participants WHERE user_id = ? AND participant = 'devices'
                """, Boolean.class, userId)).isTrue();

    }

    // A row that is already there - a migration gave an in-flight deletion its participant - must neither fail the
    // promotion nor lose an acknowledgement that has already arrived.
    @Test
    void insertRequired_participantAlreadyThere_returnsZeroAndKeepsTheRowAsItWas() {

        UUID userId = insertDeletingUser();

        participantRepository.insertRequired(userId, "devices");
        jdbc.update("UPDATE user_deletion_participants SET acked_at = CURRENT_TIMESTAMP WHERE user_id = ?", userId);

        int again = participantRepository.insertRequired(userId, "devices");

        assertThat(again).isZero();
        assertThat(jdbc.queryForObject("SELECT acked_at IS NOT NULL FROM user_deletion_participants WHERE user_id = ?",
                Boolean.class, userId)).isTrue();

    }

    @Test
    void insertRequired_severalParticipants_insertsOneRowEach() {

        UUID userId = insertDeletingUser();

        participantRepository.insertRequired(userId, "devices");
        participantRepository.insertRequired(userId, "usage");

        List<String> names = jdbc.queryForList(
                "SELECT participant FROM user_deletion_participants WHERE user_id = ? ORDER BY participant", String.class, userId);
        assertThat(names).containsExactly("devices", "usage");

    }

    // The foreign key and the name check still apply: a failure here must surface, not be swallowed by DO NOTHING
    // (which only covers a duplicate key).
    @Test
    void insertRequired_unknownUser_isRefusedByTheForeignKey() {

        assertThatThrownBy(() -> participantRepository.insertRequired(UUID.randomUUID(), "devices"))
                .isInstanceOf(DataIntegrityViolationException.class);

    }

    @Test
    void insertRequired_invalidName_isRefusedByTheNameCheck() {

        UUID userId = insertDeletingUser();

        assertThatThrownBy(() -> participantRepository.insertRequired(userId, "Devices "))
                .isInstanceOf(DataIntegrityViolationException.class);

    }

    private UUID insertDeletingUser() {

        UUID id = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO users (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for, deletion_started_at)
                VALUES (?, ?, 'Deleted user', 'DELETING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, id, "deleted+" + id + "@invalid");

        return id;

    }
}
