-- Transactional outbox for every domain event user-service publishes (UserCreated/Updated/Deleted,
-- AlertRuleCreated/Updated/Deleted - one shared Kafka topic). The @Transactional method that
-- changes users/alert_rules writes a row here in the SAME transaction, instead of calling
-- kafkaTemplate.send() directly - that would be a dual write (Postgres commits, the Kafka publish
-- fails, or the reverse). A separate relay (a @Scheduled poller for now; Debezium/CDC is the
-- planned upgrade) reads unpublished rows and sends them to Kafka.
--
-- Column shape (aggregate_type / aggregate_id / event_type / payload) deliberately mirrors the
-- Debezium outbox-event-router convention - its exact default field names are
-- configurable, so this doesn't need to match byte-for-byte, just the shape. That means swapping
-- the poller for real CDC later is a Kafka Connect config change, not a data-model rewrite.
--
-- Deferred columns (not needed for correctness today, additive later - no table rewrite):
--   * attempts / last_error / last_attempted_at - retry is already implicit in the poll design (a
--     failed publish just leaves published_at null for the next tick); these would only matter
--     for monitoring "stuck" rows specifically, a separate concern from the base shape.
--   * trace_id / correlation_id - this project has no correlation-id propagation anywhere yet
--     (a separate, project-wide gap) - adding it only here now would be building ahead of
--     infrastructure that doesn't exist. Revisit together if that's ever added project-wide.
CREATE TABLE outbox_events (
    id              BIGSERIAL     PRIMARY KEY,
    aggregate_type  VARCHAR(30)   NOT NULL,
    aggregate_id    BIGINT        NOT NULL,
    partition_key   BIGINT        NOT NULL,
    event_type      VARCHAR(40)   NOT NULL,
    payload         JSONB         NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),
    published_at    TIMESTAMPTZ,

    CONSTRAINT chk_outbox_events_aggregate_type
        CHECK (aggregate_type IN ('USER', 'ALERT_RULE')),

    CONSTRAINT chk_outbox_events_event_type
        CHECK (event_type IN (
            'USER_CREATED', 'USER_UPDATED', 'USER_DELETED',
            'ALERT_RULE_CREATED', 'ALERT_RULE_UPDATED', 'ALERT_RULE_DELETED'
        ))
);

-- The relay's only query is "give me pending rows, oldest first". A partial index on exactly that
-- predicate stays small and fast forever, no matter how many published rows pile up over time -
-- it never has to scan or index the (ever-growing) published history to find the (small) pending
-- slice.
CREATE INDEX idx_outbox_events_pending ON outbox_events (id) WHERE published_at IS NULL;
