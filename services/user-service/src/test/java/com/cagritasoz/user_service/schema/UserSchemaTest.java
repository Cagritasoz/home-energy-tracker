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

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// Tests the DATABASE, not the Java: everything here is plain SQL through JdbcTemplate, with no
// entities or repositories involved. If one of these fails, the migration is wrong (or someone
// changed it), never the application code. That is why it lives apart from the repository tests.
//
// Rollback: @DataJpaTest wraps every test in a transaction that is rolled back at the end, so the
// rows inserted below vanish and the next test starts with an empty users table.
//
// The tests follow the order of the users table in V5 (columns, then CHECK constraints, then the
// unique index) and end with V6's trigger. Inside each group the accepted case comes first, then
// the rejected one. Every rejected case is its own test (or its own parameterized invocation)
// because in Postgres a failed statement aborts the whole transaction: nothing can run after it.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
class UserSchemaTest {

    @Autowired
    private JdbcTemplate jdbc;

    // ---------------------------------------------------------------------------------------------
    // Primary key
    // ---------------------------------------------------------------------------------------------

    // pk_users on id. The second row has a different email on purpose, so the primary key is the only
    // thing it can collide with. This is the guarantee insertIgnoringConflict's ON CONFLICT (id)
    // depends on. Spring translates a unique violation (SQLSTATE 23505) to DuplicateKeyException.
    @Test
    void pkUsers_duplicateId_isRejected() {

        UUID id = UUID.randomUUID();

        insertActiveUser(id, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME);

        assertThatThrownBy(() -> insertActiveUser(id, UserFixtures.LEON_KENNEDY_EMAIL, UserFixtures.LEON_KENNEDY_NAME))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("pk_users");

    }

    // ---------------------------------------------------------------------------------------------
    // NOT NULL columns and defaults
    // ---------------------------------------------------------------------------------------------

    // Accepted side: the smallest possible insert (id, email, display_name) and every other column
    // must fill itself in. This is what pins the table's DEFAULTs - and what shows the three
    // timestamp columns without a default really are nullable.
    @Test
    void defaults_minimalInsert_fillsEveryOtherColumn() {

        UUID id = UUID.randomUUID();

        insertActiveUser(id, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", id);

        assertThat(row)
                .containsEntry("timezone", "UTC")
                .containsEntry("status", "ACTIVE")
                .containsEntry("version", 0L)
                .containsEntry("devices_deleted", false)
                .containsEntry("keycloak_disabled_at", null)
                .containsEntry("deletion_requested_at", null)
                .containsEntry("deleted_at", null)
                .doesNotContainEntry("created_at", null)
                .doesNotContainEntry("updated_at", null);

    }

    // Rejected side: an explicit NULL in each NOT NULL column. Leaving a column out is not the same
    // thing - a column with a DEFAULT just gets its default - so the NULL is written out. The row is
    // otherwise complete, and the message names the column, so the right column is the one failing.
    @ParameterizedTest
    @ValueSource(strings = {"id", "email", "display_name", "timezone", "status", "version",
            "devices_deleted", "created_at", "updated_at"})
    void notNullColumns_explicitNull_isRejected(String column) {

        assertThatThrownBy(() -> insertUserWithNullColumn(column))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("null value in column \"" + column + "\"");

    }

    // ---------------------------------------------------------------------------------------------
    // CHECK constraints
    // ---------------------------------------------------------------------------------------------

    // chk_users_status is: CHECK (status IN ('ACTIVE','DELETING','DELETED')).
    // Accepted side first. Nothing fails here, so one transaction can hold all three inserts - each
    // gets its own email because uq_users_email would otherwise reject the second live row.
    // DELETING and DELETED also carry the timestamp their lifecycle state implies:
    // chk_users_deleted_consistency insists that DELETED and deleted_at go together, so leaving it
    // out would fail on that constraint and hide what this test is about.
    @Test
    void chkUsersStatus_allThreeLegalStatuses_areAccepted() {

        assertThatCode(() -> {
            insertActiveUser(UUID.randomUUID(), UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME);
            insertDeletingUser(UUID.randomUUID(), UserFixtures.LEON_KENNEDY_EMAIL, UserFixtures.LEON_KENNEDY_NAME);
            insertDeletedUser(UUID.randomUUID(), UserFixtures.JOHN_MARSTON_EMAIL, UserFixtures.JOHN_MARSTON_NAME);
        }).doesNotThrowAnyException();

    }

    // Rejected side. Every value runs as its own invocation with its own transaction, rolled back
    // afterwards - which is how a failing insert fits in a test at all.
    //
    // The row is otherwise fully valid (good email, deleted_at left null, which is consistent with a
    // non-DELETED status), so chk_users_status is the only constraint that can possibly fire.
    // hasMessageContaining(...) pins that down: a bare DataIntegrityViolationException would also
    // pass if some other constraint had rejected the row.
    //
    // "active" and "ACTIVE " are here on purpose: the CHECK is an exact, case-sensitive comparison,
    // so a lower-case or padded value is just as illegal as nonsense.
    @ParameterizedTest
    @ValueSource(strings = {"SPLEEF!", "active", "ACTIVE ", ""})
    void chkUsersStatus_illegalStatus_isRejected(String illegalStatus) {

        // A CHECK violation (SQLSTATE 23514) is translated by Spring to DataIntegrityViolationException.
        assertThatThrownBy(() -> insertUserWithStatus(UUID.randomUUID(), UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, illegalStatus))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_users_status");

    }

    // chk_users_email is: CHECK (position('@' in email) > 1). It is a deliberately cheap last-resort
    // guard, not email validation (that is Bean Validation's job at the API), so anything with an '@'
    // after the first character passes - including nonsense. These values pin that down, so nobody
    // later mistakes the leniency for a missing case.
    @ParameterizedTest
    @ValueSource(strings = {"a@", "valid@example.com", "non!@nonsense.co!"})
    void chkUsersEmail_atSignAfterFirstCharacter_isAccepted(String email) {

        assertThatCode(() -> insertActiveUser(UUID.randomUUID(), email, UserFixtures.ARTHUR_MORGAN_NAME))
                .doesNotThrowAnyException();

    }

    // The two ways to fail: '@' as the very first character (position 1, so "@" and "@example.com")
    // and no '@' at all (position 0, so "arthur.example.com" and the empty string).
    @ParameterizedTest
    @ValueSource(strings = {"@", "@example.com", "arthur.example.com", ""})
    void chkUsersEmail_illegalEmail_isRejected(String email) {

        assertThatThrownBy(() -> insertActiveUser(UUID.randomUUID(), email, UserFixtures.ARTHUR_MORGAN_NAME))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_users_email");

    }

    // chk_users_deleted_consistency is: CHECK ((status = 'DELETED') = (deleted_at IS NOT NULL)) -
    // "DELETED" and "has a deleted_at" must always go together. It is an equality of two booleans, so
    // it can be broken in two directions; both are tested below.
    @Test
    void chkUsersDeletedConsistency_statusDeletedAndDeletedAtIsPresent_isAccepted() {

        assertThatCode(() -> insertDeletedUser(UUID.randomUUID(), UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME))
                .doesNotThrowAnyException();

    }

    // Direction 1: DELETED with deleted_at still null. insertUserWithStatus sets no timestamp, so
    // passing "DELETED" gives exactly the illegal combination this test wants.
    @Test
    void chkUsersDeletedConsistency_statusDeletedAndDeletedAtIsNull_isRejected() {

        assertThatThrownBy(() -> insertUserWithStatus(UUID.randomUUID(), UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, "DELETED"))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_users_deleted_consistency");

    }

    // Direction 2: a deleted_at on an account that is not DELETED (i.e. "deleted" timestamp on a
    // live account). Both live statuses are checked.
    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "DELETING"})
    void chkUsersDeletedConsistency_statusNotDeletedButDeletedAtIsPresent_isRejected(String liveStatus) {

        assertThatThrownBy(() -> insertUserWithStatusAndDeletedAt(UUID.randomUUID(), UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, liveStatus))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_users_deleted_consistency");

    }

    // ---------------------------------------------------------------------------------------------
    // Unique index
    // ---------------------------------------------------------------------------------------------

    // uq_users_email is: UNIQUE (lower(email)) WHERE status <> 'DELETED'. That one line says three
    // things: (1) rows with status DELETED are ignored by the index, so a former account's email is
    // free again; (2) the comparison is case-insensitive; (3) between live rows (ACTIVE/DELETING) a
    // duplicate is rejected. The tests take them in that order: the accepted side first, then the
    // rejections.
    @Test
    void uqUsersEmail_emailOfDeletedUser_canBeReused() {

        // Arthur's old account: soft-deleted, so status DELETED with deleted_at filled in
        // (chk_users_deleted_consistency requires the two to agree).
        insertDeletedUser(UUID.randomUUID(), UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME);

        // A new ACTIVE account takes the same email. Accepted - the DELETED row is outside the
        // index, so there is nothing for it to clash with.
        assertThatCode(() -> insertActiveUser(UUID.randomUUID(), UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME))
                .doesNotThrowAnyException();

        Integer rows = jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE email = ?", Integer.class, UserFixtures.ARTHUR_MORGAN_EMAIL);
        assertThat(rows).isEqualTo(2);

    }

    // The first value is the fixture's email exactly; the other two differ only in case. The index
    // compares lower(email), so all three collide with the existing ACTIVE row even though the text
    // differs. Literals rather than String.toUpperCase(): that method depends on the JVM's locale
    // (Turkish turns 'i' into 'İ'), and a test should not depend on the machine running it.
    @ParameterizedTest
    @ValueSource(strings = {"arthur.morgan@example.com", "ARTHUR.MORGAN@EXAMPLE.COM", "Arthur.Morgan@Example.com"})
    void uqUsersEmail_liveDuplicateInAnyCase_isRejected(String duplicateEmail) {

        insertActiveUser(UUID.randomUUID(), UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME);

        assertThatThrownBy(() -> insertActiveUser(UUID.randomUUID(), duplicateEmail, UserFixtures.ARTHUR_MORGAN_NAME))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_users_email");

    }

    // DELETING is still a live account (it can be cancelled or finalized later), so it keeps
    // holding its email - only DELETED releases it.
    @Test
    void uqUsersEmail_emailOfDeletingUser_isStillTaken() {

        insertDeletingUser(UUID.randomUUID(), UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME);

        assertThatThrownBy(() -> insertActiveUser(UUID.randomUUID(), UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_users_email");

    }

    // ---------------------------------------------------------------------------------------------
    // V6: updated_at trigger
    // ---------------------------------------------------------------------------------------------

    // These two tests insert with an explicit, old created_at/updated_at (year 2000) instead of
    // relying on the defaults, and that is essential, not decoration: CURRENT_TIMESTAMP in Postgres
    // is the START TIME OF THE TRANSACTION, and each test runs inside one. An insert and an update
    // in the same test would therefore get the identical timestamp, and "updated_at changed" could
    // never be observed - the test would fail even with a perfectly working trigger.
    private static final String YEAR_2000 = "2000-01-01T00:00:00Z";

    // BEFORE UPDATE trigger: updated_at is stamped on every UPDATE, and the trigger touches nothing
    // else. version in particular is left alone on purpose - Hibernate's @Version (or an explicit
    // version + 1 in a native update) owns it - and created_at must never move.
    @Test
    void trgUsersSetUpdatedAt_update_bumpsUpdatedAtAndLeavesCreatedAtAndVersionAlone() {

        UUID id = UUID.randomUUID();
        insertUserWithOldTimestamps(id);

        jdbc.update("UPDATE users SET display_name = ? WHERE id = ?", UserFixtures.LEON_KENNEDY_NAME, id);

        assertThat(timestampOf("updated_at", id)).isAfter(Instant.parse(YEAR_2000));
        assertThat(timestampOf("created_at", id)).isEqualTo(Instant.parse(YEAR_2000));
        assertThat(jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Long.class, id)).isZero(); // Trigger does not touch version.

    }

    // The quirk: no writer can bring its own clock. Here the UPDATE explicitly sets updated_at to a
    // date in the past, and the trigger overwrites it anyway - the database owns time.
    @Test
    void trgUsersSetUpdatedAt_writerSuppliedUpdatedAt_isOverwritten() {

        UUID id = UUID.randomUUID();
        insertUserWithOldTimestamps(id);

        jdbc.update("UPDATE users SET updated_at = '1999-01-01T00:00:00Z' WHERE id = ?", id);

        assertThat(timestampOf("updated_at", id)).isAfter(Instant.parse(YEAR_2000));

    }

    // ---------------------------------------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------------------------------------

    // For the tests that need to control status directly, including illegal values. Sets no
    // timestamps, so a legal row only results for statuses that don't need one (not DELETED) - the
    // tests that pass DELETED here do it on purpose, to provoke chk_users_deleted_consistency.
    private void insertUserWithStatus(UUID id, String email, String displayName, String status) {

        jdbc.update("INSERT INTO users (id, email, display_name, status) VALUES (?, ?, ?, ?)",
                id, email, displayName, status);

    }

    // Only the columns without defaults are given; status, version, timestamps etc. come from the
    // table's DEFAULTs - which is itself exercised by the defaults test.
    private void insertActiveUser(UUID id, String email, String displayName) {

        jdbc.update("INSERT INTO users (id, email, display_name) VALUES (?, ?, ?)",
                id, email, displayName);

    }

    private void insertDeletingUser(UUID id, String email, String displayName) {

        jdbc.update("INSERT INTO users (id, email, display_name, status, deletion_requested_at) VALUES (?, ?, ?, 'DELETING', CURRENT_TIMESTAMP)",
                id, email, displayName);

    }

    private void insertDeletedUser(UUID id, String email, String displayName) {

        jdbc.update("INSERT INTO users (id, email, display_name, status, deleted_at) VALUES (?, ?, ?, 'DELETED', CURRENT_TIMESTAMP)",
                id, email, displayName);

    }

    // A deleted_at on any status - used to build the "not DELETED but has a deleted_at" combination.
    private void insertUserWithStatusAndDeletedAt(UUID id, String email, String displayName, String status) {

        jdbc.update("INSERT INTO users (id, email, display_name, status, deleted_at) VALUES (?, ?, ?, ?, CURRENT_TIMESTAMP)",
                id, email, displayName, status);

    }

    // A complete, valid row in which exactly one column is an explicit NULL. The map is ordered, so
    // the column list and the values line up.
    private void insertUserWithNullColumn(String nullColumn) {

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", UUID.randomUUID());
        values.put("email", UserFixtures.ARTHUR_MORGAN_EMAIL);
        values.put("display_name", UserFixtures.ARTHUR_MORGAN_NAME);
        values.put("timezone", "UTC");
        values.put("status", "ACTIVE");
        values.put("version", 0L);
        values.put("devices_deleted", false);
        values.put("created_at", OffsetDateTime.now());
        values.put("updated_at", OffsetDateTime.now());

        values.put(nullColumn, null);

        String columns = String.join(", ", values.keySet());
        String placeholders = String.join(", ", Collections.nCopies(values.size(), "?"));

        jdbc.update("INSERT INTO users (" + columns + ") VALUES (" + placeholders + ")", values.values().toArray());

    }

    private void insertUserWithOldTimestamps(UUID id) {

        jdbc.update("INSERT INTO users (id, email, display_name, created_at, updated_at) VALUES (?, ?, ?, ?::timestamptz, ?::timestamptz)",
                id, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME, YEAR_2000, YEAR_2000);

    }

    // Read as an Instant so the comparison doesn't depend on the session's time zone or offset.
    private Instant timestampOf(String column, UUID id) {

        return Objects.requireNonNull(jdbc.queryForObject("SELECT " + column + " FROM users WHERE id = ?", Instant.class, id));

    }
}
