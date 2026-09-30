package com.cagritasoz.user_service.schema;

import com.cagritasoz.contracts.user.UserEventType;
import com.cagritasoz.contracts.user.UserEvents;
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
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
class OutboxEventSchemaTest {

    private static final OffsetDateTime YEAR_2026 = OffsetDateTime.parse("2026-01-01T00:00:00Z");

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void seq_consecutiveInserts_increaseInInsertOrder() {

        UUID first = insertEvent(Map.of());
        UUID second = insertEvent(Map.of());
        UUID third = insertEvent(Map.of());

        List<UUID> idsBySeq = jdbc.queryForList(
                "SELECT id FROM outbox_events WHERE id IN (?, ?, ?) ORDER BY seq", UUID.class, first, second, third);

        assertThat(idsBySeq).containsExactly(first, second, third);

    }

    @Test
    void seq_explicitValue_isRejected() {

        assertThatThrownBy(() -> insertEvent(Map.of("seq", 1L)))
                .isInstanceOf(BadSqlGrammarException.class)
                .rootCause()
                .hasMessageContaining("cannot insert a non-DEFAULT value into column \"seq\"");

    }

    @Test
    void uqOutboxEventsId_duplicateEventId_isRejected() {

        UUID id = insertEvent(Map.of());

        assertThatThrownBy(() -> insertEvent(Map.of("id", id)))
                .isInstanceOf(DuplicateKeyException.class)
                .hasMessageContaining("uq_outbox_events_id");

    }

    @Test
    void defaults_minimalInsert_fillsEveryOtherColumn() {

        UUID id = insertEvent(Map.of());

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM outbox_events WHERE id = ?", id);

        assertThat(row)
                .containsEntry("aggregate_version", null)
                .containsEntry("schema_version", 1)
                .containsEntry("correlation_id", null)
                .containsEntry("causation_id", null)
                .containsEntry("published_at", null)
                .containsEntry("attempts", 0)
                .containsEntry("last_error", null)
                .doesNotContainEntry("seq", null)
                .doesNotContainEntry("created_at", null);

    }

    @Test
    void optionalColumns_allFilled_areAccepted() {

        UUID id = insertEvent(Map.of(
                "aggregate_version", 7L,
                "correlation_id", UUID.randomUUID(),
                "causation_id", UUID.randomUUID(),
                "published_at", YEAR_2026,
                "attempts", 3,
                "last_error", "org.apache.kafka.common.errors.TimeoutException"));

        Map<String, Object> row = jdbc.queryForMap("SELECT * FROM outbox_events WHERE id = ?", id);

        assertThat(row)
                .containsEntry("aggregate_version", 7L)
                .containsEntry("attempts", 3)
                .doesNotContainEntry("correlation_id", null)
                .doesNotContainEntry("causation_id", null)
                .doesNotContainEntry("published_at", null)
                .doesNotContainEntry("last_error", null);

    }

    @ParameterizedTest
    @ValueSource(strings = {"id", "aggregate_type", "aggregate_id", "event_type", "schema_version", "topic",
            "payload", "occurred_at", "created_at", "attempts"})
    void notNullColumns_explicitNull_isRejected(String column) {

        assertThatThrownBy(() -> insertEvent(Collections.singletonMap(column, null)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("null value in column \"" + column + "\"");

    }

    @Test
    void chkOutboxEventsSchemaVersion_one_isAccepted() {

        assertThatCode(() -> insertEvent(Map.of("schema_version", 1)))
                .doesNotThrowAnyException();

    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void chkOutboxEventsSchemaVersion_belowOne_isRejected(int schemaVersion) {

        assertThatThrownBy(() -> insertEvent(Map.of("schema_version", schemaVersion)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_outbox_events_schema_version");

    }

    @Test
    void chkOutboxEventsAttempts_zero_isAccepted() {

        assertThatCode(() -> insertEvent(Map.of("attempts", 0)))
                .doesNotThrowAnyException();

    }

    @Test
    void chkOutboxEventsAttempts_negative_isRejected() {

        assertThatThrownBy(() -> insertEvent(Map.of("attempts", -1)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_outbox_events_attempts");

    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"eventId\": \"x\", \"data\": {\"nested\": [1, 2]}}"}) // Empty JSON Object, Valid JSON Object.
    void chkOutboxEventsPayloadIsObject_jsonObject_isAccepted(String payload) {

        assertThatCode(() -> insertEvent(Map.of("payload", payload)))
                .doesNotThrowAnyException();

    }

    @ParameterizedTest
    @ValueSource(strings = {"[]", "[{}]", "\"text\"", "42", "true", "null"})
    void chkOutboxEventsPayloadIsObject_nonObjectJson_isRejected(String payload) {

        assertThatThrownBy(() -> insertEvent(Map.of("payload", payload)))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("chk_outbox_events_payload_is_object");

    }

    @Test
    void payload_invalidJson_isRejected() {

        assertThatThrownBy(() -> insertEvent(Map.of("payload", "{not json")))
                .isInstanceOf(DataIntegrityViolationException.class)
                .hasMessageContaining("invalid input syntax for type json");

    }

    @Test
    void payload_storedAsJsonb_isQueryableByField() {

        UUID id = insertEvent(Map.of("payload", "{\"eventType\": \"UserRegistered\", \"data\": {\"timezone\": \"UTC\"}}"));

        String timezone = jdbc.queryForObject(
                "SELECT payload -> 'data' ->> 'timezone' FROM outbox_events WHERE id = ?", String.class, id);

        assertThat(timezone).isEqualTo("UTC");

    }

    @Test
    void idxOutboxEventsPending_isPartialOnUnpublishedRowsOrderedBySeq() { // Index definition check.

        assertThat(indexDefinition("idx_outbox_events_pending"))
                .contains("(seq)")
                .contains("WHERE (published_at IS NULL)");

    }

    @Test
    void idxOutboxEventsPublished_isPartialOnPublishedRowsOrderedByPublishedAt() {

        assertThat(indexDefinition("idx_outbox_events_published"))
                .contains("(published_at)")
                .contains("WHERE (published_at IS NOT NULL)");

    }

    private UUID insertEvent(Map<String, ?> overrides) {

        Map<String, Object> row = new LinkedHashMap<>();
        row.put("id", UUID.randomUUID());
        row.put("aggregate_type", UserEvents.AGGREGATE_TYPE);
        row.put("aggregate_id", UUID.randomUUID().toString());
        row.put("event_type", UserEventType.USER_REGISTERED.value());
        row.put("topic", UserEvents.TOPIC);
        row.put("payload", "{}");
        row.put("occurred_at", YEAR_2026);
        row.putAll(overrides);

        String columns = String.join(", ", row.keySet());
        String placeholders = row.keySet().stream()
                .map(column -> column.equals("payload") ? "?::jsonb" : "?")
                .collect(Collectors.joining(", "));

        jdbc.update("INSERT INTO outbox_events (" + columns + ") VALUES (" + placeholders + ")", row.values().toArray());

        return (UUID) row.get("id");

    }

    private String indexDefinition(String indexName) {

        return jdbc.queryForObject(
                "SELECT indexdef FROM pg_indexes WHERE tablename = 'outbox_events' AND indexname = ?", String.class, indexName);

    }
}
