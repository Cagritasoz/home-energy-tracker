-- outbox_events: the transactional outbox.
--
-- A service that changes its database and then publishes to Kafka has two writes and no transaction spanning
-- both: crash in between and the event is lost, or announces a change that never committed. So the event is
-- inserted HERE, in the same transaction as the change, and a relay publishes the committed rows afterwards. The
-- relay can crash between "sent" and "marked sent", so delivery is at-least-once and consumers deduplicate on id.
--
--  - A row is the COMPLETE event: payload holds the whole envelope and data, so consumers receive the same bytes
--    whether a polling relay or (later) Debezium publishes it.
--  - Pending is not a status column: published_at IS NULL is pending, anything else is done.
--  - Business code only inserts. The relay updates published_at, attempts and last_error; the purge job deletes
--    published rows older than the replay window.

CREATE TABLE outbox_events (

    -- What happened, and to whom
    seq                bigint      GENERATED ALWAYS AS IDENTITY CONSTRAINT pk_outbox_events PRIMARY KEY,    -- publishing order
    id                 uuid        NOT NULL,                                                                -- the event id; consumers deduplicate on it
    aggregate_type     text        NOT NULL,
    aggregate_id       text        NOT NULL,                                                                -- also the Kafka key: one aggregate, one partition
    aggregate_version  bigint,                                                                              -- the aggregate's version after the change (has gaps)
    event_type         text        NOT NULL,
    schema_version     int         NOT NULL DEFAULT 1,
    topic              text        NOT NULL,
    payload            jsonb       NOT NULL,
    correlation_id     uuid,
    causation_id       uuid,
    occurred_at        timestamptz NOT NULL,                                                                -- when the change happened
    created_at         timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,                                      -- when the row was written

    -- The relay's state
    published_at       timestamptz,                                                                         -- Kafka acknowledged it
    parked             boolean     NOT NULL DEFAULT false,                                                  -- set aside: it can never be published (e.g. too large)
    attempts           int         NOT NULL DEFAULT 0,
    last_error         text,

    CONSTRAINT uq_outbox_events_id UNIQUE (id),

    CONSTRAINT chk_outbox_events_schema_version CHECK (schema_version >= 1),

    CONSTRAINT chk_outbox_events_attempts CHECK (attempts >= 0),

    CONSTRAINT chk_outbox_events_payload_is_object CHECK (jsonb_typeof(payload) = 'object'),

    -- Kafka's default message limit is about 1 MB; 256 KB keeps well clear of it. Measured on the stored jsonb
    -- rendered as text, which is close to (not identical with) what the relay sends.
    CONSTRAINT chk_outbox_events_payload_size CHECK (octet_length(payload::text) < 262144),

    -- A row Kafka would refuse (illegal characters, too long, "." or "..") would stall the relay on it forever,
    -- and a missing topic only shows up as metadata timeouts that look transient.
    CONSTRAINT chk_outbox_events_topic_format CHECK (topic ~ '^[A-Za-z0-9._-]{1,249}$' AND topic NOT IN ('.', '..')),

    -- A parked row has, by definition, not been published.
    CONSTRAINT chk_outbox_events_parked_unpublished CHECK (NOT parked OR published_at IS NULL)
);

-- The relay's hot query, "oldest pending rows in order". Partial, so it only holds the (normally tiny) backlog.
CREATE INDEX idx_outbox_events_pending   ON outbox_events (seq)          WHERE published_at IS NULL AND NOT parked;

-- The purge job ("published rows older than the replay window") and replay by range. Only published rows.
CREATE INDEX idx_outbox_events_published ON outbox_events (published_at) WHERE published_at IS NOT NULL;

-- The parked-rows metric and un-parking.
CREATE INDEX idx_outbox_events_parked    ON outbox_events (seq)          WHERE parked;
