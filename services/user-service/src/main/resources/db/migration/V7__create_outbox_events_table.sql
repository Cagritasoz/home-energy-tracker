-- Transactional outbox: the reason this table exists. A service that changes its own database and
-- then publishes to Kafka has two writes and no transaction spanning both - crash in between and
-- the event is lost (or announces a change that never committed). Instead, the event is inserted
-- HERE, in the same transaction as the change, and a separate relay publishes committed rows to
-- Kafka afterwards. The relay can crash between "sent" and "marked sent", so an event can be
-- delivered more than once: at-least-once, with consumers deduplicating on `id`.
--
-- Table rules of thumb:
--  - the row is the COMPLETE event: `payload` holds the whole envelope + data, so what consumers
--    receive would be byte-identical whether a polling relay or (later) Debezium publishes it;
--  - "pending" is not a status column: published_at IS NULL is pending, anything else is done;
--  - rows are never updated by business code, only by the relay (published_at/attempts/last_error)
--    and deleted by the purge job once they are older than the replay window.
--
-- The layout follows the outbox design under docs/design, adapted to this project's naming
-- (outbox_events, pk_/uq_/chk_/idx_ prefixes, CURRENT_TIMESTAMP like V5). Not in this migration:
-- processed_events (the consumer-side dedupe table) - it arrives with user-service's first
-- consumer, UserDevicesDeleted.

CREATE TABLE outbox_events (
    seq                bigint      GENERATED ALWAYS AS IDENTITY CONSTRAINT pk_outbox_events PRIMARY KEY,
    id                 uuid        NOT NULL,
    aggregate_type     text        NOT NULL,
    aggregate_id       text        NOT NULL,
    aggregate_version  bigint,
    event_type         text        NOT NULL,
    schema_version     int         NOT NULL DEFAULT 1,
    topic              text        NOT NULL,
    payload            jsonb       NOT NULL,
    correlation_id     uuid,
    causation_id       uuid,
    occurred_at        timestamptz NOT NULL,
    created_at         timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    published_at       timestamptz,
    attempts           int         NOT NULL DEFAULT 0,
    last_error         text,

    CONSTRAINT uq_outbox_events_id UNIQUE (id),
    CONSTRAINT chk_outbox_events_schema_version CHECK (schema_version >= 1),
    CONSTRAINT chk_outbox_events_attempts CHECK (attempts >= 0),
    CONSTRAINT chk_outbox_events_payload_is_object CHECK (jsonb_typeof(payload) = 'object')
);

-- The relay's hot query: "oldest pending rows, in order". Partial, so it only ever contains the
-- (normally tiny) backlog and stays small no matter how many published rows pile up.
CREATE INDEX idx_outbox_events_pending ON outbox_events (seq) WHERE published_at IS NULL;

-- Serves the purge job ("published rows older than the replay window") and replay-by-range without
-- a full table scan. Partial for the same reason, the other way round: only published rows.
CREATE INDEX idx_outbox_events_published ON outbox_events (published_at) WHERE published_at IS NOT NULL;
