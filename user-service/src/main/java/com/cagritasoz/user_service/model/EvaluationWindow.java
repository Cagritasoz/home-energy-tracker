package com.cagritasoz.user_service.model;

// The trailing time span a rule's threshold is checked over. Kept as a plain enum here: user-service
// only stores and validates the choice. The window -> Duration mapping that actually drives the
// InfluxDB range query belongs in usage-service, which will hold its own copy of this enum (same
// "don't share a module across services" stance as the Kafka event records).
// Values must stay in sync with V3's chk_alert_rules_evaluation_window CHECK list.
public enum EvaluationWindow {
    TEN_MINUTES,
    THIRTY_MINUTES,
    ONE_HOUR,
    TWO_HOURS,
    THREE_HOURS,
    SIX_HOURS,
    TWELVE_HOURS,
    ONE_DAY
}
