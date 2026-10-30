-- V9 allowed keycloak_deleted_at only on DELETED rows, because the Keycloak user used to be removed last.
-- The order is now: the promoter sets DELETING, a job hard-deletes the Keycloak user while the row is
-- DELETING (and records the time here), and the finalizer marks the row DELETED once a quiet period
-- has passed after that. So the timestamp must be allowed from DELETING on.

ALTER TABLE users DROP CONSTRAINT chk_users_keycloak_deleted_only_deleted;

ALTER TABLE users
    ADD CONSTRAINT chk_users_keycloak_deleted_only_deleting_or_deleted
        CHECK (keycloak_deleted_at IS NULL OR status IN ('DELETING', 'DELETED'));
