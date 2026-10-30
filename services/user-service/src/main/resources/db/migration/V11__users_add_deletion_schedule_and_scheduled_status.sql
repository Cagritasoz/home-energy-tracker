-- Account deletion gets a grace period. The user asks for deletion (ACTIVE -> DELETION_SCHEDULED) and can
-- still cancel; a promoter job moves the row to DELETING once deletion_scheduled_for has passed.
--
--   deletion_requested_at  when the user asked (exists since V5, kept)
--   deletion_scheduled_for when the grace period ends. An absolute time computed in SQL when the user asks,
--                          so changing the configured grace period later moves no existing row.
--   deletion_started_at    when the promoter started the cleanup (DELETING)
--
-- ADD CONSTRAINT checks every existing row and fails the whole migration if one breaks a rule.

ALTER TABLE users
    ADD COLUMN deletion_scheduled_for timestamptz,
    ADD COLUMN deletion_started_at    timestamptz;

-- Rows that are already DELETING or DELETED were never given a grace period: both new timestamps take
-- the time they were requested. (The V6 trigger stamps updated_at on this UPDATE; that is harmless.)
UPDATE users
SET deletion_scheduled_for = deletion_requested_at,
    deletion_started_at    = deletion_requested_at
WHERE status IN ('DELETING', 'DELETED');

ALTER TABLE users DROP CONSTRAINT chk_users_status;
ALTER TABLE users
    ADD CONSTRAINT chk_users_status CHECK (status IN ('ACTIVE', 'DELETION_SCHEDULED', 'DELETING', 'DELETED'));

-- Each timestamp exists exactly in the statuses that imply it, so an impossible saga state cannot be stored.
-- (V9 already ties deletion_requested_at to "not ACTIVE".)
ALTER TABLE users
    ADD CONSTRAINT chk_users_deletion_scheduled_consistency CHECK ((status = 'ACTIVE') = (deletion_scheduled_for IS NULL));

ALTER TABLE users
    ADD CONSTRAINT chk_users_deletion_started_consistency
        CHECK ((status IN ('DELETING', 'DELETED')) = (deletion_started_at IS NOT NULL));

-- The grace period cannot end before the request. A NULL on either side passes, which is correct for ACTIVE rows.
ALTER TABLE users
    ADD CONSTRAINT chk_users_deletion_scheduled_after_requested CHECK (deletion_scheduled_for >= deletion_requested_at);

-- One partial index per job scan, each small forever because most rows are ACTIVE or DELETED:
-- the promoter looks for scheduled rows whose time has come, the Keycloak delete job and the
-- finalizer look at the DELETING rows by age.
CREATE INDEX idx_users_deletion_scheduled ON users (deletion_scheduled_for) WHERE status = 'DELETION_SCHEDULED';

DROP INDEX idx_users_deleting;
CREATE INDEX idx_users_deleting ON users (deletion_started_at) WHERE status = 'DELETING';
