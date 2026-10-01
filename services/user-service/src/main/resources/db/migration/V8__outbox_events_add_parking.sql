-- Parking: a row that can never be published (e.g. too large for the broker) is set aside instead of
-- blocking every row behind it. The relay skips parked rows; they stay here, with last_error saying
-- why, until someone fixes the cause and un-parks them.

ALTER TABLE outbox_events
    ADD COLUMN parked boolean NOT NULL DEFAULT false;

-- A parked row has, by definition, not been published.
ALTER TABLE outbox_events
    ADD CONSTRAINT chk_outbox_events_parked_unpublished CHECK (NOT parked OR published_at IS NULL);

-- Kafka's default message limit is about 1 MB; 256 KB keeps well clear of it. Measured on the stored
-- jsonb rendered as text, which is close to (not byte-identical with) what the relay sends.
ALTER TABLE outbox_events
    ADD CONSTRAINT chk_outbox_events_payload_size CHECK (octet_length(payload::text) < 262144);


DROP INDEX idx_outbox_events_pending;
CREATE INDEX idx_outbox_events_pending ON outbox_events (seq) WHERE published_at IS NULL AND NOT parked;

-- For the parked-rows metric and for un-parking.
CREATE INDEX idx_outbox_events_parked ON outbox_events (seq) WHERE parked;
