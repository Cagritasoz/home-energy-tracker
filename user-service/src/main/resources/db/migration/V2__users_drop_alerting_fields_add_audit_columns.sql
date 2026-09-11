-- Alerting configuration moves out of the users table into its own alert_rules table (V3).
-- A user no longer carries a single global threshold plus an on/off switch; they now own zero
-- or more independently-toggleable rules. These two columns have no direct replacement on users.
ALTER TABLE users DROP COLUMN alerts_enabled;
ALTER TABLE users DROP COLUMN energy_alerting_threshold;

-- Audit columns. DEFAULT now() covers the initial INSERT
-- The application keeps updated_at current on
-- subsequent writes via Hibernate's @UpdateTimestamp;
ALTER TABLE users ADD COLUMN created_at TIMESTAMPTZ NOT NULL DEFAULT now();
ALTER TABLE users ADD COLUMN updated_at TIMESTAMPTZ NOT NULL DEFAULT now();
