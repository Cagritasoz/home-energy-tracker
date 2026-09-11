-- User-defined energy alert rules. Each row means: "notify me when my energy consumption over
-- <evaluation_window> exceeds <threshold_kwh>". Owned and managed by user-service; usage-service
-- reads a cached snapshot of the enabled rows to drive its scheduled evaluation and publishes a
-- ThresholdExceeded event for every rule that trips for alerting-service.
--
-- Deferred columns (intentionally left out for now - each is an additive change later, no table
-- rewrite: a nullable ADD COLUMN or a widened CHECK):
--   * scope_ref VARCHAR - target id/type once scope grows PER_DEVICE / PER_DEVICE_TYPE / PER_LOCATION.
--     When it lands, the uniqueness constraint below extends to include it.
--   * version   BIGINT  - optimistic locking (@Version) if concurrent edits of the same rule matter.
CREATE TABLE alert_rules (
    id                BIGSERIAL     PRIMARY KEY,
    user_id           BIGINT        NOT NULL,
    name              VARCHAR(100)  NOT NULL,
    evaluation_window VARCHAR(20)   NOT NULL,
    threshold_kwh     NUMERIC(10,3) NOT NULL,
    scope             VARCHAR(20)   NOT NULL DEFAULT 'ALL_DEVICES',
    enabled           BOOLEAN       NOT NULL DEFAULT TRUE,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at        TIMESTAMPTZ   NOT NULL DEFAULT now(),

    -- Intra-service relationship: both tables live in the user_service schema, so unlike
    -- devices.user_id (which points across a service boundary and therefore gets no FK) a real
    -- foreign key is correct here. ON DELETE CASCADE means deleting a user removes their rules
    -- in the same statement - no application code, no orphan rows.
    CONSTRAINT fk_alert_rules_user
        FOREIGN KEY (user_id) REFERENCES users (id) ON DELETE CASCADE,

    -- Bounded set of windows so usage-service can batch evaluation (one InfluxDB aggregation
    -- query per distinct window per tick, not one per user). Same CHECK-list pattern as
    -- devices.device_type.
    CONSTRAINT chk_alert_rules_evaluation_window
        CHECK (evaluation_window IN ('TEN_MINUTES', 'THIRTY_MINUTES', 'ONE_HOUR', 'TWO_HOURS', 'THREE_HOURS', 'SIX_HOURS', 'TWELVE_HOURS', 'ONE_DAY')),

    CONSTRAINT chk_alert_rules_threshold_positive
        CHECK (threshold_kwh > 0),

    -- Only ALL_DEVICES at the moment; the column and CHECK exist now so adding PER_DEVICE etc. later is
    -- a one-line CHECK widened rather than a schema redesign.
    CONSTRAINT chk_alert_rules_scope
        CHECK (scope IN ('ALL_DEVICES')),

    -- At most one rule per (user, window, scope). A second rule with the same window and scope
    -- but a different threshold is almost always a mistake - the lower threshold always fires
    -- first and the higher one is dead config. Users edit the existing rule instead.
    -- The index backing this constraint has user_id as its leftmost column, so it also serves
    -- "all rules for user X" lookups and the ON DELETE CASCADE - a standalone index on user_id
    -- would be redundant.
    CONSTRAINT uq_alert_rules_user_window_scope
        UNIQUE (user_id, evaluation_window, scope)
);
