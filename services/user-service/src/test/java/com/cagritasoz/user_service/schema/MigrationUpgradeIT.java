package com.cagritasoz.user_service.schema;

import com.cagritasoz.user_service.testsupport.PostgresTestContainerConfig;
import com.cagritasoz.user_service.testsupport.UserFixtures;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// An existing database, not an empty one: migrate to an older version, insert rows the way that version's
// app wrote them, then run the newer migrations over them. No Spring context - the shared test database is
// already at the latest version, so this test drives Flyway itself, one fresh schema per test.
@Testcontainers
class MigrationUpgradeIT {

    private static final String LAST_RELEASED_VERSION = "7";

    // The schema before the deletion grace period (V11 onwards), where DELETING rows exist without the new timestamps.
    private static final String BEFORE_DELETION_SCHEDULE_VERSION = "10";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(PostgresTestContainerConfig.POSTGRES_IMAGE);

    @Test
    void migrate_fromV7WithConsistentRows_appliesEveryNewerMigrationAndKeepsTheRows() {

        String schema = "upgrade_consistent";
        migrateTo(schema, LAST_RELEASED_VERSION);
        JdbcTemplate jdbc = jdbcFor(schema);

        UUID active = UUID.randomUUID();
        UUID deleting = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();

        jdbc.update("INSERT INTO users (id, email, display_name) VALUES (?, ?, ?)",
                active, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME);
        jdbc.update("""
                        INSERT INTO users (id, email, display_name, status, version, deletion_requested_at, keycloak_disabled_at)
                        VALUES (?, ?, ?, 'DELETING', 1, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """,
                deleting, UserFixtures.LEON_KENNEDY_EMAIL, UserFixtures.LEON_KENNEDY_NAME);
        jdbc.update("""
                        INSERT INTO users (id, email, display_name, status, version, devices_deleted, deletion_requested_at, keycloak_disabled_at, deleted_at)
                        VALUES (?, ?, ?, 'DELETED', 2, true, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                        """,
                deleted, UserFixtures.JOHN_MARSTON_EMAIL, UserFixtures.JOHN_MARSTON_NAME);

        insertOutboxRow(jdbc, active, "CURRENT_TIMESTAMP");
        insertOutboxRow(jdbc, deleting, "NULL");

        migrateTo(schema, "latest");

        assertThat(currentVersion(schema)).isEqualTo("14"); // Change as more migrations come.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE keycloak_deleted_at IS NULL", Integer.class)).isEqualTo(3); // Added in V9.
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, deleting)).isEqualTo("DELETING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE parked", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL AND NOT parked", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_deletion_participants", Integer.class)).isZero(); // Added in V13.

        // V11 gave the DELETING and DELETED rows the timestamps their status needs; the ACTIVE row has none.
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE status = 'ACTIVE' AND deletion_scheduled_for IS NULL AND deletion_started_at IS NULL",
                Integer.class)).isOne();
        assertThat(jdbc.queryForObject(
                "SELECT count(*) FROM users WHERE status IN ('DELETING', 'DELETED') AND deletion_scheduled_for IS NOT NULL AND deletion_started_at IS NOT NULL",
                Integer.class)).isEqualTo(2);

        // V14 dropped the two columns of the first saga design; their data was not needed by the new one.
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM information_schema.columns
                WHERE table_schema = ? AND table_name = 'users' AND column_name IN ('devices_deleted', 'keycloak_disabled_at')
                """, Integer.class, schema)).isZero();

    }

    // The case the grace period's migration exists for: rows that are already DELETING or DELETED when it arrives
    // were never scheduled, so they are backfilled as "scheduled and started at the moment they were requested"
    // (an immediate deletion). Their requested time, status and version must be untouched.
    @Test
    void migrate_fromV10WithDeletingAndDeletedRows_backfillsTheScheduleColumnsFromTheRequestTime() {

        String schema = "upgrade_deletion_schedule";
        migrateTo(schema, BEFORE_DELETION_SCHEDULE_VERSION);
        JdbcTemplate jdbc = jdbcFor(schema);

        UUID deleting = UUID.randomUUID();
        UUID deleted = UUID.randomUUID();
        UUID active = UUID.randomUUID();

        jdbc.update("INSERT INTO users (id, email, display_name) VALUES (?, ?, ?)",
                active, UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME);
        jdbc.update("""
                        INSERT INTO users (id, email, display_name, status, version, deletion_requested_at, keycloak_disabled_at)
                        VALUES (?, ?, ?, 'DELETING', 3, '2026-03-01T10:00:00Z', '2026-03-01T10:00:05Z')
                        """,
                deleting, UserFixtures.LEON_KENNEDY_EMAIL, UserFixtures.LEON_KENNEDY_NAME);
        jdbc.update("""
                        INSERT INTO users (id, email, display_name, status, version, devices_deleted, deletion_requested_at,
                                           keycloak_disabled_at, deleted_at, keycloak_deleted_at)
                        VALUES (?, ?, ?, 'DELETED', 4, true, '2026-02-01T08:00:00Z', '2026-02-01T08:00:05Z',
                                '2026-02-01T08:00:20Z', '2026-02-01T08:00:20Z')
                        """,
                deleted, UserFixtures.JOHN_MARSTON_EMAIL, UserFixtures.JOHN_MARSTON_NAME);

        migrateTo(schema, "latest");

        assertThat(instant(jdbc, "deletion_requested_at", deleting)).isEqualTo(Instant.parse("2026-03-01T10:00:00Z"));
        assertThat(instant(jdbc, "deletion_scheduled_for", deleting)).isEqualTo(Instant.parse("2026-03-01T10:00:00Z"));
        assertThat(instant(jdbc, "deletion_started_at", deleting)).isEqualTo(Instant.parse("2026-03-01T10:00:00Z"));
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, deleting)).isEqualTo("DELETING");
        assertThat(jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Long.class, deleting)).isEqualTo(3L);

        assertThat(instant(jdbc, "deletion_scheduled_for", deleted)).isEqualTo(Instant.parse("2026-02-01T08:00:00Z"));
        assertThat(instant(jdbc, "deletion_started_at", deleted)).isEqualTo(Instant.parse("2026-02-01T08:00:00Z"));
        assertThat(instant(jdbc, "keycloak_deleted_at", deleted)).isEqualTo(Instant.parse("2026-02-01T08:00:20Z"));

        assertThat(jdbc.queryForObject("SELECT deletion_scheduled_for FROM users WHERE id = ?", Instant.class, active)).isNull();

        // The migrated DELETING row is a full citizen of the new schema: the finished-deletion write the saga
        // will make is accepted by the new constraints (a Keycloak delete is allowed while DELETING).
        assertThat(jdbc.update("UPDATE users SET keycloak_deleted_at = CURRENT_TIMESTAMP WHERE id = ?", deleting)).isOne();

    }

    // V5 allowed a DELETED row without deletion_requested_at; V9 forbids it. The migration must fail
    // rather than store the row as it is - and V8, which ran before it in its own transaction, stays applied.
    @Test
    void migrate_fromV7WithDeletedRowMissingDeletionRequestedAt_failsAtV9() {

        String schema = "upgrade_inconsistent";
        migrateTo(schema, LAST_RELEASED_VERSION);
        JdbcTemplate jdbc = jdbcFor(schema);

        jdbc.update("INSERT INTO users (id, email, display_name, status, deleted_at) VALUES (?, ?, ?, 'DELETED', CURRENT_TIMESTAMP)",
                UUID.randomUUID(), UserFixtures.ARTHUR_MORGAN_EMAIL, UserFixtures.ARTHUR_MORGAN_NAME);

        assertThatThrownBy(() -> migrateTo(schema, "latest"))
                .isInstanceOf(FlywayException.class)
                .hasMessageContaining("chk_users_deleting_consistency");

        assertThat(currentVersion(schema)).isEqualTo("8"); // V9 is not applied as expected.

    }

    private static Instant instant(JdbcTemplate jdbc, String column, UUID userId) {

        return jdbc.queryForObject("SELECT " + column + " FROM users WHERE id = ?", Instant.class, userId);

    }

    private static void insertOutboxRow(JdbcTemplate jdbc, UUID userId, String publishedAt) {

        jdbc.update("""
                        INSERT INTO outbox_events (id, aggregate_type, aggregate_id, aggregate_version, event_type, topic, payload, occurred_at, published_at)
                        VALUES (?, 'User', ?, 0, 'UserRegistered', 'user.events.v1', '{}'::jsonb, CURRENT_TIMESTAMP, %s)
                        """.formatted(publishedAt),
                UUID.randomUUID(), userId.toString());

    }

    private static Flyway flyway(String schema, String target) {

        return Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .schemas(schema)
                .target(target)
                .load();

    }

    private static void migrateTo(String schema, String target) {

        flyway(schema, target).migrate();

    }

    private static String currentVersion(String schema) {

        return flyway(schema, "latest").info().current().getVersion().getVersion();

    }

    // Build the JdbcTemplate to be used.
    private static JdbcTemplate jdbcFor(String schema) {

        String url = POSTGRES.getJdbcUrl();
        String separator = url.contains("?") ? "&" : "?";

        return new JdbcTemplate(new DriverManagerDataSource(
                url + separator + "currentSchema=" + schema, POSTGRES.getUsername(), POSTGRES.getPassword()));

    }
}
