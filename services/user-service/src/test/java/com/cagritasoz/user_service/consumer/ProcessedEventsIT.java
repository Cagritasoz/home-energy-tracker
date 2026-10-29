package com.cagritasoz.user_service.consumer;

import com.cagritasoz.user_service.testsupport.containers.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The claim and the purge against a real Postgres. Rolled-back transactions; CURRENT_TIMESTAMP is the start of the
// test's transaction, so rows seeded "30 days ago" are exactly 30 days old when the purge runs.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JdbcClientAutoConfiguration.class)
@Import({PostgresTestContainerConfig.class, ProcessedEvents.class})
class ProcessedEventsIT {

    @Autowired
    private ProcessedEvents processedEvents;

    @Autowired
    private JdbcTemplate jdbc;

    // ---- claim --------------------------------------------------------------------------------------

    @Test
    void claim_firstTime_isTrueAndStoresTheRow() {

        UUID eventId = UUID.randomUUID();

        assertThat(processedEvents.claim(eventId, "h", "UserDevicesUnclaimed", "agg-1")).isTrue();

        assertThat(jdbc.queryForMap("SELECT event_type, aggregate_id, processed_at IS NOT NULL AS stamped FROM processed_events WHERE event_id = ?", eventId))
                .containsEntry("event_type", "UserDevicesUnclaimed")
                .containsEntry("aggregate_id", "agg-1")
                .containsEntry("stamped", true);

    }

    @Test
    void claim_sameEventAndHandlerAgain_isFalseAndKeepsTheFirstRow() {

        UUID eventId = UUID.randomUUID();
        processedEvents.claim(eventId, "h", "First", "agg-1");

        assertThat(processedEvents.claim(eventId, "h", "Second", "agg-2")).isFalse();

        assertThat(jdbc.queryForList("SELECT event_type FROM processed_events WHERE event_id = ?", String.class, eventId))
                .containsExactly("First");

    }

    @Test
    void claim_sameEventForAnotherHandler_isANewClaim() {

        UUID eventId = UUID.randomUUID();

        assertThat(processedEvents.claim(eventId, "one", "T", "a")).isTrue();
        assertThat(processedEvents.claim(eventId, "two", "T", "a")).isTrue();

    }

    // ---- purge --------------------------------------------------------------------------------------

    private void insertClaimAged(String age) {

        jdbc.update("INSERT INTO processed_events (event_id, handler, event_type, aggregate_id, processed_at) "
                + "VALUES (?, 'h', 'T', 'a', CURRENT_TIMESTAMP - CAST(? AS interval))", UUID.randomUUID(), age);

    }

    private Integer remaining() {

        return jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class);

    }

    // Strictly older than the retention goes; a row exactly at the boundary, and anything younger, stays.
    @Test
    void purgeOlderThan_deletesOnlyRowsOlderThanTheRetention() {

        insertClaimAged("31 days"); // Gone
        insertClaimAged("30 days 1 second"); // Gone, rest stays.
        insertClaimAged("30 days");
        insertClaimAged("29 days");
        insertClaimAged("1 minute");

        assertThat(processedEvents.purgeOlderThan(30, 100)).isEqualTo(2);

        assertThat(remaining()).isEqualTo(3);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE processed_at < CURRENT_TIMESTAMP - interval '30 days'",
                Integer.class)).isZero();

    }

    @Test
    void purgeOlderThan_atMostOneBatch_oldestFirst() {

        insertClaimAged("60 days");
        insertClaimAged("50 days");
        insertClaimAged("40 days");

        assertThat(processedEvents.purgeOlderThan(30, 2)).isEqualTo(2);

        // The newest of the three old rows is the one left.
        List<Boolean> leftIsTheYoungest = jdbc.queryForList(
                "SELECT processed_at > CURRENT_TIMESTAMP - interval '45 days' FROM processed_events", Boolean.class);
        assertThat(leftIsTheYoungest).containsExactly(true);

        assertThat(processedEvents.purgeOlderThan(30, 2)).isEqualTo(1);
        assertThat(processedEvents.purgeOlderThan(30, 2)).isZero();

    }

    @Test
    void purgeOlderThan_emptyTable_deletesNothing() {

        assertThat(processedEvents.purgeOlderThan(30, 100)).isZero();

    }
}
