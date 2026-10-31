-- users: one row per customer account.
--
-- The id is the Keycloak subject (the OIDC "sub" claim, a UUID) and is never generated here. A row is soft
-- deleted (personal data scrubbed, status DELETED), never removed, so other tables may reference it for good.
-- The account-deletion saga drives the status:
--
--   ACTIVE -> DELETION_SCHEDULED -> DELETING -> DELETED       (DELETION_SCHEDULED -> ACTIVE: the user cancels)
--
-- The database owns time: created_at has a default and a trigger stamps updated_at on every UPDATE, whoever
-- issues it, so no writer can forget it or bring its own clock.

CREATE TABLE users (

    -- Identity
    id                     uuid        CONSTRAINT pk_users PRIMARY KEY,
    email                  text        NOT NULL,
    display_name           text        NOT NULL,
    timezone               text        NOT NULL DEFAULT 'UTC',

    -- Lifecycle. Each deletion timestamp exists exactly in the statuses that imply it (checks below).
    status                 text        NOT NULL DEFAULT 'ACTIVE',
    deletion_requested_at  timestamptz,    -- the user asked for the deletion
    deletion_scheduled_for timestamptz,    -- end of the grace period: an absolute time, so changing the configured grace moves no existing row
    deletion_started_at    timestamptz,    -- the promoter began the cleanup (DELETING)
    keycloak_deleted_at    timestamptz,    -- the Keycloak user was hard-deleted (while DELETING)
    deleted_at             timestamptz,    -- the finalizer closed the deletion (DELETED)

    -- Bookkeeping
    version                bigint      NOT NULL DEFAULT 0,    -- optimistic lock; the trigger leaves it alone, native updates add 1 themselves
    created_at             timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    updated_at             timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT chk_users_status
        CHECK (status IN ('ACTIVE', 'DELETION_SCHEDULED', 'DELETING', 'DELETED')),

    -- Deliberately cheap, not real email validation (Bean Validation does that at the API): a last-resort guard
    -- against an obviously malformed value arriving some other way.
    CONSTRAINT chk_users_email
        CHECK (position('@' in email) > 1),

    CONSTRAINT chk_users_deleting_consistency
        CHECK ((status = 'ACTIVE') = (deletion_requested_at IS NULL)),

    CONSTRAINT chk_users_deletion_scheduled_consistency
        CHECK ((status = 'ACTIVE') = (deletion_scheduled_for IS NULL)),

    CONSTRAINT chk_users_deletion_started_consistency
        CHECK ((status IN ('DELETING', 'DELETED')) = (deletion_started_at IS NOT NULL)),

    CONSTRAINT chk_users_keycloak_deleted_only_deleting_or_deleted
        CHECK (keycloak_deleted_at IS NULL OR status IN ('DELETING', 'DELETED')),

    CONSTRAINT chk_users_deleted_consistency
        CHECK ((status = 'DELETED') = (deleted_at IS NOT NULL)),

    -- The grace period cannot end before the request. A NULL on either side passes, which is right for ACTIVE rows.
    CONSTRAINT chk_users_deletion_scheduled_after_requested
        CHECK (deletion_scheduled_for >= deletion_requested_at)
);

-- Case-insensitive uniqueness without the citext extension (the application applies lower() on write and on
-- lookup). Partial on status <> 'DELETED': a scrubbed account must not squat on its old address, or the same email
-- could never register again. It doubles as the index on lower(email).
CREATE UNIQUE INDEX uq_users_email ON users (lower(email)) WHERE status <> 'DELETED';

-- One partial index per job scan, each small forever because most rows are ACTIVE or DELETED: the promoter looks
-- for scheduled rows whose time has come; the Keycloak delete job and the finalizer read the DELETING rows by age.
CREATE INDEX idx_users_deletion_scheduled ON users (deletion_scheduled_for) WHERE status = 'DELETION_SCHEDULED';
CREATE INDEX idx_users_deleting           ON users (deletion_started_at)    WHERE status = 'DELETING';

CREATE FUNCTION set_updated_at() RETURNS trigger AS $$
BEGIN
    NEW.updated_at = CURRENT_TIMESTAMP;
    RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER trg_users_set_updated_at
    BEFORE UPDATE ON users
    FOR EACH ROW EXECUTE FUNCTION set_updated_at();
