-- User identity moves from an app-generated BIGSERIAL to the Keycloak subject (the OIDC "sub"
-- claim, a UUID) - see the Keycloak setup in infra/docker-compose.yml and infra/keycloak/. That
-- id-type change is breaking for every table that referenced the old users.id, so this migration
-- resets the whole user_service schema rather than trying to retrofit each one individually.
--
-- Drop order matters here, not just cosmetic: alert_rules.user_id is a live FK to users(id), so
-- it has to go before users or the DROP fails with a dependency error. outbox_events has no FK to
-- either and could drop anywhere, but goes first anyway to read top-to-bottom as "most temporary/
-- least permanent first" alongside the comments below explaining each table's fate.

-- Fully redesigned once that design exists, not just retyped for UUID - not guessed at here.
-- processed_events (for idempotent event consumption) arrives alongside it, same migration.
DROP TABLE outbox_events;

-- Gone for good, not reset: alert rules move to their own dedicated service in the new design, so
-- this table - and every alert_rule-related class that used to live in user-service - has no
-- future in this schema at all.
DROP TABLE alert_rules;

DROP TABLE users;

CREATE TABLE users (
    id                    uuid        PRIMARY KEY,
    email                 text        NOT NULL,
    display_name          text        NOT NULL,
    timezone              text        NOT NULL DEFAULT 'UTC',
    status                text        NOT NULL DEFAULT 'ACTIVE',
    version               bigint      NOT NULL DEFAULT 0,
    devices_deleted       boolean     NOT NULL DEFAULT false,
    deletion_requested_at timestamptz,
    deleted_at            timestamptz,
    created_at            timestamptz NOT NULL DEFAULT now(),
    updated_at            timestamptz NOT NULL DEFAULT now(),

    CONSTRAINT users_status_chk CHECK (status IN ('ACTIVE','DELETING','DELETED')),

    -- Deliberately cheap, not real email validation (that's Bean Validation's job at the API
    -- boundary) - just a last-resort guard against an obviously-malformed email value reaching the DB
    -- some other way (a direct insert, a future backfill script, ...).
    CONSTRAINT users_email_chk  CHECK (position('@' in email) > 1),

    -- Makes "status says DELETED but deleted_at is still null" (or the reverse) impossible at the
    -- DB level. The finalizer and anything downstream can rely on status = 'DELETED' implying
    -- deleted_at is populated, with no defensive null-check needed.
    CONSTRAINT users_deleted_consistency_chk
        CHECK ((status = 'DELETED') = (deleted_at IS NOT NULL))
);

-- Case-insensitive uniqueness (lower()) without needing the citext extension - the application
-- just has to apply lower() consistently on write and on lookup. Partial on status <> 'DELETED':
-- a scrubbed/former account's leftover email must not permanently squat on uniqueness, since this
-- is soft delete, not a real row removal - otherwise the same email could never be re-registered.
-- This index provides conditional uniqueness: lowercased email must be unique
-- among all rows whose status is not DELETED, while also providing an index
-- on lower(email).
CREATE UNIQUE INDEX users_email_uq ON users (lower(email)) WHERE status <> 'DELETED';

-- Serves exactly the finalizer's scan ("DELETING rows whose grace period has elapsed") without a
-- full table scan. Partial on status = 'DELETING' keeps the index small forever - the ACTIVE and
-- DELETED rows that make up the overwhelming majority over time are excluded entirely.
CREATE INDEX users_deleting_idx ON users (deletion_requested_at) WHERE status = 'DELETING';

-- outbox            : Will be reintroduced in a later migration.
-- processed_events  : Will be reintroduced in a later migration.
