package com.cagritasoz.user_service.schema;

import com.cagritasoz.user_service.testsupport.containers.PostgresTestContainerConfig;
import com.cagritasoz.user_service.testsupport.data.JdbcParameters;
import com.cagritasoz.user_service.testsupport.data.UserFixtures;
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
// The tests follow the order of the users table in V1 (columns, then CHECK constraints, then the
// unique index) and end with the trigger. Inside each group the accepted case comes first, then
// the rejected one. Every rejected case is its own test (or its own parameterized invocation)
// because in Postgres a failed statement aborts the whole transaction: nothing can run after it.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
class UserSchemaIT {

    @Autowired
    private JdbcTemplate jdbc;

    // ---------------------------------------------------------------------------------------------
    // Primary key
    // ---------------------------------------------------------------------------------------------

    // pk_users on id. The second row has a different email on purpose, so the primary key is the only
    // thing it can collide with. This is one of the two guarantees insertIgnoringConflict's
    // ON CONFLICT DO NOTHING depends on (the other is uq_users_email). Spring translates a unique violation (SQLSTATE 23505) to DuplicateKeyException.
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
    // must fill itself in. This is what pins the table's DEFAULTs - and what shows the deletion
    // timestamp columns, which have no default, really are nullable.
    @Test
    void defaults_minimalInsert_fillsEveryOtherColumn() {

        UUID id = UUID.randomUUID();

        insertActiveUser(id, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME);

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM users WHERE id = ?", id);

        assertThat(row)
                .containsEntry("timezone", "UTC")
                .containsEntry("status", "ACTIVE")
                .containsEntry("version", 0L)
                .containsEntry("deletion_requested_at", null)
                .containsEntry("deletion_scheduled_for", null)
                .containsEntry("deletion_started_at", null)
                .containsEntry("keycloak_deleted_at", null)
                .containsEntry("deleted_at", null)
                .doesNotContainEntry("created_at", null)
                .doesNotContainEntry("updated_at", null);

    }

    // Rejected side: an explicit NULL in each NOT NULL column. Leaving a column out is not the same
    // thing - a column with a DEFAULT just gets its default - so the NULL is written out. The row is
    // otherwise complete, and the message names the column, so the right column is the one failing.
    @ParameterizedTest
    @ValueSource(strings = {"id", "email", "display_name", "timezone", "status", "version",
            "created_at", "updated_at"})
    void notNullColumns_explicitNull_isRejected(String column) {

        assertThatThrownBy(() -> insertUserWithNullColumn(column))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("null value in column \"" + column + "\"");

    }

    // ---------------------------------------------------------------------------------------------
    // CHECK constraints
    // ---------------------------------------------------------------------------------------------

    // chk_users_status is: CHECK (status IN ('ACTIVE','DELETION_SCHEDULED','DELETING','DELETED')).
    // Accepted side first. Nothing fails here, so one transaction can hold all four inserts - each
    // gets its own email because uq_users_email would otherwise reject the second live row.
    // Every non-ACTIVE status also carries the timestamps its lifecycle state implies (the
    // deletion_* consistency checks and chk_users_deleted_consistency insist on them), so leaving
    // them out would fail on those constraints and hide what this test is about.
    @Test
    void chkUsersStatus_allFourLegalStatuses_areAccepted() {

        assertThatCode(() -> {
            insertActiveUser(UUID.randomUUID(), UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME);
            insertScheduledUser(UUID.randomUUID(), UserFixtures.LEON_KENNEDY_EMAIL, UserFixtures.LEON_KENNEDY_NAME);
            insertDeletingUser(UUID.randomUUID(), UserFixtures.JOHN_MARSTON_EMAIL, UserFixtures.JOHN_MARSTON_NAME);
            insertDeletedUser(UUID.randomUUID(), UserFixtures.JACK_CARVER_EMAIL, UserFixtures.JACK_CARVER_NAME);
        }).doesNotThrowAnyException();

    }

    // Rejected side. Every value runs as its own invocation with its own transaction, rolled back
    // afterwards - which is how a failing insert fits in a test at all.
    //
    // The row is otherwise fully valid (good email, deleted_at left null, which is consistent with a
    // non-DELETED status, and the timestamps of a non-ACTIVE row), so chk_users_status is the only
    // constraint that can possibly fire.
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

    // chk_users_deleting_consistency is: CHECK ((status = 'ACTIVE') = (deletion_requested_at IS NULL)).
    // Accepted side: chkUsersStatus_allFourLegalStatuses_areAccepted above inserts every status with its
    // matching timestamps. The rejected rows carry every other timestamp their status needs (columnsFor),
    // so each test can only fail on the constraint it names.
    @Test
    void chkUsersDeletingConsistency_activeWithDeletionRequestedAt_isRejected() {

        assertThatThrownBy(() -> insertUserWith(Map.of("status", "ACTIVE", "deletion_requested_at", YEAR_2026)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_users_deleting_consistency");

    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETION_SCHEDULED", "DELETING", "DELETED"})
    void chkUsersDeletingConsistency_notActiveWithoutDeletionRequestedAt_isRejected(String status) {

        Map<String, Object> columns = columnsFor(status);
        columns.remove("deletion_requested_at");

        assertThatThrownBy(() -> insertUserWith(columns))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_users_deleting_consistency");

    }

    // chk_users_deletion_scheduled_consistency is: CHECK ((status = 'ACTIVE') = (deletion_scheduled_for IS NULL)).
    @Test
    void chkUsersDeletionScheduledConsistency_activeWithScheduledFor_isRejected() {

        assertThatThrownBy(() -> insertUserWith(Map.of("status", "ACTIVE", "deletion_scheduled_for", YEAR_2026)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_users_deletion_scheduled_consistency");

    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETION_SCHEDULED", "DELETING", "DELETED"})
    void chkUsersDeletionScheduledConsistency_notActiveWithoutScheduledFor_isRejected(String status) {

        Map<String, Object> columns = columnsFor(status);
        columns.remove("deletion_scheduled_for");

        assertThatThrownBy(() -> insertUserWith(columns))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_users_deletion_scheduled_consistency");

    }

    // chk_users_deletion_started_consistency is:
    // CHECK ((status IN ('DELETING','DELETED')) = (deletion_started_at IS NOT NULL)).
    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "DELETION_SCHEDULED"})
    void chkUsersDeletionStartedConsistency_activeOrScheduledWithStartedAt_isRejected(String status) {

        Map<String, Object> columns = columnsFor(status);
        columns.put("deletion_started_at", YEAR_2026);

        assertThatThrownBy(() -> insertUserWith(columns))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_users_deletion_started_consistency");

    }

    @ParameterizedTest
    @ValueSource(strings = {"DELETING", "DELETED"})
    void chkUsersDeletionStartedConsistency_deletingOrDeletedWithoutStartedAt_isRejected(String status) {

        Map<String, Object> columns = columnsFor(status);
        columns.remove("deletion_started_at");

        assertThatThrownBy(() -> insertUserWith(columns))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_users_deletion_started_consistency");

    }

    // chk_users_deletion_scheduled_after_requested is: CHECK (deletion_scheduled_for >= deletion_requested_at).
    // The accepted cases: the same instant (an immediate deletion) and a grace period after the request.
    @Test
    void chkUsersDeletionScheduledAfterRequested_sameInstantAndLaterInstant_areAccepted() {

        Map<String, Object> immediate = columnsFor("DELETION_SCHEDULED");

        Map<String, Object> withGracePeriod = columnsFor("DELETION_SCHEDULED");
        withGracePeriod.put("deletion_scheduled_for", YEAR_2026.plusSeconds(300));

        assertThatCode(() -> {
            insertUserWith(withEmail(immediate, UserFixtures.ARTHUR_MORGAN_EMAIL));
            insertUserWith(withEmail(withGracePeriod, UserFixtures.LEON_KENNEDY_EMAIL));
        }).doesNotThrowAnyException();

    }

    @Test
    void chkUsersDeletionScheduledAfterRequested_scheduledBeforeRequested_isRejected() {

        Map<String, Object> columns = columnsFor("DELETION_SCHEDULED");
        columns.put("deletion_scheduled_for", YEAR_2026.minusSeconds(1));

        assertThatThrownBy(() -> insertUserWith(columns))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_users_deletion_scheduled_after_requested");

    }

    // chk_users_keycloak_deleted_only_deleting_or_deleted is:
    // CHECK (keycloak_deleted_at IS NULL OR status IN ('DELETING','DELETED')) - the Keycloak user is deleted
    // while the row is DELETING, so the timestamp is allowed from then on and never before.
    @ParameterizedTest
    @ValueSource(strings = {"DELETING", "DELETED"})
    void chkUsersKeycloakDeleted_deletingOrDeletedWithKeycloakDeletedAt_isAccepted(String status) {

        Map<String, Object> columns = columnsFor(status);
        columns.put("keycloak_deleted_at", YEAR_2026);

        assertThatCode(() -> insertUserWith(columns)).doesNotThrowAnyException();

    }

    @ParameterizedTest
    @ValueSource(strings = {"ACTIVE", "DELETION_SCHEDULED"})
    void chkUsersKeycloakDeleted_activeOrScheduledWithKeycloakDeletedAt_isRejected(String status) {

        Map<String, Object> columns = columnsFor(status);
        columns.put("keycloak_deleted_at", YEAR_2026);

        assertThatThrownBy(() -> insertUserWith(columns))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_users_keycloak_deleted_only_deleting_or_deleted");

    }

    // ---------------------------------------------------------------------------------------------
    // Unique index
    // ---------------------------------------------------------------------------------------------

    // uq_users_email is: UNIQUE (lower(email)) WHERE status <> 'DELETED'. That one line says three
    // things: (1) rows with status DELETED are ignored by the index, so a former account's email is
    // free again; (2) the comparison is case-insensitive; (3) between live rows (ACTIVE,
    // DELETION_SCHEDULED, DELETING) a duplicate is rejected. The tests take them in that order: the accepted side first, then the
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

    // DELETION_SCHEDULED and DELETING are still live accounts (the first can be cancelled, the second is not
    // finished yet), so they keep holding their email - only DELETED releases it.
    @ParameterizedTest
    @ValueSource(strings = {"DELETION_SCHEDULED", "DELETING"})
    void uqUsersEmail_emailOfScheduledOrDeletingUser_isStillTaken(String status) {

        Map<String, Object> columns = columnsFor(status);
        columns.put("id", UUID.randomUUID());
        columns.put("email", UserFixtures.ARTHUR_MORGAN_EMAIL);
        columns.put("display_name", UserFixtures.ARTHUR_MORGAN_NAME);
        insertUserWith(columns);

        assertThatThrownBy(() -> insertActiveUser(UUID.randomUUID(), UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_users_email");

    }

    // ---------------------------------------------------------------------------------------------
    // the deletion columns and their indexes
    // ---------------------------------------------------------------------------------------------

    // The two columns of the first saga design are gone and the three new ones exist.
    @Test
    void columns_deletionScheduleExists_andTheFirstDesignsColumnsAreGone() {

        List<String> columns = jdbc.queryForList(
                "SELECT column_name FROM information_schema.columns WHERE table_schema = current_schema() AND table_name = 'users'",
                String.class);

        assertThat(columns).contains("deletion_requested_at", "deletion_scheduled_for", "deletion_started_at", "keycloak_deleted_at");
        assertThat(columns).doesNotContain("devices_deleted", "keycloak_disabled_at");

    }

    // One partial index per job scan: the promoter reads scheduled rows by time, the later jobs read DELETING rows
    // by age. Partial, so each stays small however many ACTIVE and DELETED rows there are.
    @Test
    void indexes_deletionScansUsePartialIndexesOnTheirOwnStatus() {

        assertThat(indexDefinition("idx_users_deletion_scheduled"))
                .contains("(deletion_scheduled_for)")
                .contains("status = 'DELETION_SCHEDULED'");

        assertThat(indexDefinition("idx_users_deleting"))
                .contains("(deletion_started_at)")
                .contains("status = 'DELETING'");

    }

    // ---------------------------------------------------------------------------------------------
    // updated_at trigger
    // ---------------------------------------------------------------------------------------------

    // These two tests insert with an explicit, old created_at/updated_at (year 2000) instead of
    // relying on the defaults, and that is essential, not decoration: CURRENT_TIMESTAMP in Postgres
    // is the START TIME OF THE TRANSACTION, and each test runs inside one. An insert and an update
    // in the same test would therefore get the identical timestamp, and "updated_at changed" could
    // never be observed - the test would fail even with a perfectly working trigger.
    private static final String YEAR_2000 = "2000-01-01T00:00:00Z";

    private static final Instant YEAR_2026 = Instant.parse("2026-01-01T00:00:00Z");

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

    // For the tests that need to control status directly, including illegal values. A non-ACTIVE status
    // gets the timestamps its lifecycle state implies (columnsFor), but never a deleted_at - the tests that
    // pass DELETED here do it on purpose, to provoke chk_users_deleted_consistency.
    private void insertUserWithStatus(UUID id, String email, String displayName, String status) {

        Map<String, Object> columns = columnsFor(status);
        columns.remove("deleted_at");
        columns.put("id", id);
        columns.put("email", email);
        columns.put("display_name", displayName);

        insertUserWith(columns);

    }

    // Only the columns without defaults are given; status, version, timestamps etc. come from the
    // table's DEFAULTs - which is itself exercised by the defaults test.
    private void insertActiveUser(UUID id, String email, String displayName) {

        jdbc.update("INSERT INTO users (id, email, display_name) VALUES (?, ?, ?)",
                id, email, displayName);

    }

    // The user asked for deletion and the grace period (five minutes here) is running.
    private void insertScheduledUser(UUID id, String email, String displayName) {

        jdbc.update("""
                        INSERT INTO users (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for)
                        VALUES (?, ?, ?, 'DELETION_SCHEDULED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + interval '5 minutes')
                        """,
                id, email, displayName);

    }

    private void insertDeletingUser(UUID id, String email, String displayName) {

        jdbc.update("""
                        INSERT INTO users (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for, deletion_started_at)
                        VALUES (?, ?, ?, 'DELETING', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """,
                id, email, displayName);

    }

    private void insertDeletedUser(UUID id, String email, String displayName) {

        jdbc.update("""
                        INSERT INTO users (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for, deletion_started_at, deleted_at)
                        VALUES (?, ?, ?, 'DELETED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """,
                id, email, displayName);

    }

    // A deleted_at on any status - used to build the "not DELETED but has a deleted_at" combination.
    private void insertUserWithStatusAndDeletedAt(UUID id, String email, String displayName, String status) {

        Map<String, Object> columns = columnsFor(status);
        columns.put("id", id);
        columns.put("email", email);
        columns.put("display_name", displayName);
        columns.put("deleted_at", YEAR_2026);

        insertUserWith(columns);

    }

    // The status and exactly the timestamps that status requires, all at the same instant (an immediate
    // deletion is the legal minimum): requested and scheduled for from DELETION_SCHEDULED on, started from
    // DELETING on, deleted_at for DELETED. A test takes a legal row from here and breaks one thing in it.
    private static Map<String, Object> columnsFor(String status) {

        Map<String, Object> columns = new LinkedHashMap<>();
        columns.put("status", status);

        if (!status.equals("ACTIVE")) {
            columns.put("deletion_requested_at", YEAR_2026);
            columns.put("deletion_scheduled_for", YEAR_2026);
        }

        if (status.equals("DELETING") || status.equals("DELETED")) {
            columns.put("deletion_started_at", YEAR_2026);
        }

        if (status.equals("DELETED")) {
            columns.put("deleted_at", YEAR_2026);
        }

        return columns;

    }

    // A copy of the columns with another email, so two rows of one test do not meet in uq_users_email.
    private static Map<String, Object> withEmail(Map<String, Object> columns, String email) {

        Map<String, Object> copy = new LinkedHashMap<>(columns);
        copy.put("email", email);

        return copy;

    }

    private String indexDefinition(String name) {

        return jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE schemaname = current_schema() AND indexname = ?", String.class, name);

    }

    // Arthur Morgan's row with the given columns added or overridden; everything else from the defaults.
    private void insertUserWith(Map<String, ?> columns) {

        Map<String, Object> values = new LinkedHashMap<>();
        values.put("id", UUID.randomUUID());
        values.put("email", UserFixtures.ARTHUR_MORGAN_EMAIL);
        values.put("display_name", UserFixtures.ARTHUR_MORGAN_NAME);
        values.putAll(columns);

        String placeholders = String.join(", ", Collections.nCopies(values.size(), "?"));

        jdbc.update("INSERT INTO users (" + String.join(", ", values.keySet()) + ") VALUES (" + placeholders + ")",
                JdbcParameters.bindable(values.values().toArray()));

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
        values.put("created_at", Instant.now());
        values.put("updated_at", Instant.now());

        values.put(nullColumn, null);

        String columns = String.join(", ", values.keySet());
        String placeholders = String.join(", ", Collections.nCopies(values.size(), "?"));

        jdbc.update("INSERT INTO users (" + columns + ") VALUES (" + placeholders + ")", JdbcParameters.bindable(values.values().toArray()));

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
