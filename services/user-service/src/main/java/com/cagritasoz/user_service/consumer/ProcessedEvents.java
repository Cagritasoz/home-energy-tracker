package com.cagritasoz.user_service.consumer;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// The consumer's memory of what it has already applied: the processed_events table. Kafka delivers at least once, so
// every handler first claims the event id here, in the SAME transaction as the effect it is about to apply. A
// redelivered event finds the claim already there and is skipped; a failure after the claim rolls the claim back with
// the effect, so the retry applies it for real. The effect and the memory of it are atomic.
//
// (event_id, handler) is the key: the handler is part of it so that two handlers in this service can each apply the
// same event.
@Component
public class ProcessedEvents {

    private final JdbcClient jdbc;

    public ProcessedEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    // true: first time this handler sees the event, go ahead and apply it. false: applied before, do nothing.
    //
    // MANDATORY, like the outbox writer: a claim made outside the effect's transaction would survive the effect's
    // failure, and the retry would then skip an event that was never applied.
    @Transactional(propagation = Propagation.MANDATORY)
    public boolean claim(UUID eventId, String handler, String eventType, String aggregateId) {

        int inserted = jdbc.sql("""
                        INSERT INTO processed_events (event_id, handler, event_type, aggregate_id)
                        VALUES (:eventId, :handler, :eventType, :aggregateId)
                        ON CONFLICT DO NOTHING
                        """)
                .param("eventId", eventId)
                .param("handler", handler)
                .param("eventType", eventType)
                .param("aggregateId", aggregateId)
                .update();

        return inserted == 1;

    }

    // One batch of the purge: claims older than the retention, oldest first. A redelivery cannot be older than the
    // topic's retention, so a claim older than that is dead weight. Its own transaction: a purge needs no caller.
    @Transactional
    public int purgeOlderThan(int retentionDays, int batchSize) {

        return jdbc.sql("""
                        DELETE FROM processed_events
                        WHERE (event_id, handler) IN (
                            SELECT event_id, handler FROM processed_events
                            WHERE processed_at < CURRENT_TIMESTAMP - make_interval(days => :retentionDays)
                            ORDER BY processed_at
                            LIMIT :batchSize)
                        """)
                .param("retentionDays", retentionDays)
                .param("batchSize", batchSize)
                .update();

    }
}
