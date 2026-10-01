package com.cagritasoz.user_service.schema;

import com.cagritasoz.user_service.testsupport.PostgresTestContainerConfig;
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

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
class ProcessedEventSchemaTest {

    // Name of the handler.
    private static final String HANDLER = "user-devices-deleted";

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void pkProcessedEvents_sameEventAndHandler_isRejected() {

        UUID eventId = UUID.randomUUID();

        insertProcessedEvent(Map.of("event_id", eventId));

        assertThatThrownBy(() -> insertProcessedEvent(Map.of("event_id", eventId)))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("pk_processed_events");

    }

    @Test
    void pkProcessedEvents_sameEventDifferentHandler_isAccepted() {

        UUID eventId = UUID.randomUUID();

        insertProcessedEvent(Map.of("event_id", eventId));

        assertThatCode(() -> insertProcessedEvent(Map.of("event_id", eventId, "handler", "another-handler")))
                .doesNotThrowAnyException();

    }

    @Test
    void onConflictDoNothing_redeliveredEvent_insertsNoRow() {

        UUID eventId = UUID.randomUUID();
        String sql = """
                INSERT INTO processed_events (event_id, handler, event_type, aggregate_id)
                VALUES (?, ?, 'UserDevicesDeleted', ?)
                ON CONFLICT DO NOTHING
                """;

        int first = jdbc.update(sql, eventId, HANDLER, UUID.randomUUID().toString());
        int redelivery = jdbc.update(sql, eventId, HANDLER, UUID.randomUUID().toString());

        assertThat(first).isOne(); // First time delivered and processed.
        assertThat(redelivery).isZero(); // id conflict, do nothing.

    }

    @Test
    void defaults_minimalInsert_fillsProcessedAt() {

        UUID eventId = insertProcessedEvent(Map.of());

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM processed_events WHERE event_id = ?", eventId);

        assertThat(row).doesNotContainEntry("processed_at", null);

    }

    @ParameterizedTest
    @ValueSource(strings = {"event_id", "handler", "event_type", "aggregate_id", "processed_at"})
    void notNullColumns_explicitNull_isRejected(String column) {

        assertThatThrownBy(() -> insertProcessedEvent(Collections.singletonMap(column, null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("null value in column \"" + column + "\"");

    }

    @Test
    void idxProcessedEventsProcessedAt_coversThePurgeColumn() {

        String definition = jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'processed_events' AND indexname = 'idx_processed_events_processed_at'",
                String.class);

        assertThat(definition).contains("(processed_at)");

    }

    private UUID insertProcessedEvent(Map<String, ?> overrides) {

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("event_id", UUID.randomUUID());
        row.put("handler", HANDLER);
        row.put("event_type", "UserDevicesDeleted");
        row.put("aggregate_id", UUID.randomUUID().toString());
        row.putAll(overrides);

        String placeholders = String.join(", ", Collections.nCopies(row.size(), "?"));

        jdbc.update("INSERT INTO processed_events (" + String.join(", ", row.keySet()) + ") VALUES (" + placeholders + ")",
                row.values().toArray());

        return (UUID) row.get("event_id");

    }
}
