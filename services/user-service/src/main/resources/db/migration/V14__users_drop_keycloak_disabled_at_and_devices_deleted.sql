-- Two columns of the first saga design have no place in the final one:
--   keycloak_disabled_at  Keycloak is no longer disabled first; the user can still log in while deletion is
--                         scheduled, and the Keycloak user is hard-deleted during DELETING.
--   devices_deleted       a single yes/no for one service; user_deletion_participants (V13) replaces it with one
--                         row per required service.
-- Nothing in the saga reads either value, and no participant service exists as of 24.10.2026 (yet), so there is nothing to
-- carry over into the new table.

ALTER TABLE users DROP CONSTRAINT chk_users_keycloak_disabled_not_active;

ALTER TABLE users
    DROP COLUMN keycloak_disabled_at,
    DROP COLUMN devices_deleted;
