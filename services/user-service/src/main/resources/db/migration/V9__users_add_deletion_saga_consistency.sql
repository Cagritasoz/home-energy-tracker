-- Bookkeeping for the account-deletion saga: the last step (hard delete in Keycloak) gets its own
-- timestamp, and the timestamps are tied to the status that implies them, so an impossible saga state
-- can't be stored.
--
-- ADD CONSTRAINT checks every existing row and fails the whole migration if one breaks a rule.

ALTER TABLE users
    ADD COLUMN keycloak_deleted_at timestamptz;

ALTER TABLE users
    ADD CONSTRAINT chk_users_deleting_consistency CHECK ((status = 'ACTIVE') = (deletion_requested_at IS NULL));

-- Keycloak is disabled only after the deletion request commits.
ALTER TABLE users
    ADD CONSTRAINT chk_users_keycloak_disabled_not_active CHECK (keycloak_disabled_at IS NULL OR status <> 'ACTIVE');

-- Keycloak is hard-deleted only after the finalizer has marked the account DELETED.
ALTER TABLE users
    ADD CONSTRAINT chk_users_keycloak_deleted_only_deleted CHECK (keycloak_deleted_at IS NULL OR status = 'DELETED');
