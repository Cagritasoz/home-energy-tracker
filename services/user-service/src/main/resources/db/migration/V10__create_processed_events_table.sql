-- Consumer-side deduplication. Kafka delivers at least once, so a consumer inserts (event_id, handler)
-- in the same transaction as its side effect: a redelivered event hits the primary key and is skipped.
-- handler is part of the key so that two handlers in this service may each process the same event.
-- Purged after 30 days; a duplicate older than that is not expected.

CREATE TABLE processed_events (
    event_id      uuid        NOT NULL,
    handler       text        NOT NULL,
    event_type    text        NOT NULL,
    aggregate_id  text        NOT NULL,
    processed_at  timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_processed_events PRIMARY KEY (event_id, handler)
);

CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
