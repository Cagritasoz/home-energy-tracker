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

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// An existing database, not an empty one: migrate to V7, insert rows the way the V7 app wrote them,
// then run the newer migrations over them. No Spring context - the shared test database is already at
// the latest version, so this test drives Flyway itself, one fresh schema per test.
@Testcontainers
class MigrationUpgradeTest {

    private static final String LAST_RELEASED_VERSION = "7";

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

        assertThat(currentVersion(schema)).isEqualTo("10"); // Change as more migrations come.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users", Integer.class)).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE keycloak_deleted_at IS NULL", Integer.class)).isEqualTo(3); // Added in V9.
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, deleting)).isEqualTo("DELETING");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE parked", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events WHERE published_at IS NULL AND NOT parked", Integer.class)).isOne();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class)).isZero();

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
