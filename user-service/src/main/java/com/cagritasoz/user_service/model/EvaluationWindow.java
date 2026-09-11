package com.cagritasoz.user_service.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

// The trailing time span a rule's threshold is checked over. Kept as a plain enum here: user-service
// only stores and validates the choice. The window -> Duration mapping that actually drives the
// InfluxDB range query belongs in usage-service, which will hold its own copy of this enum (same
// "don't share a module across services" stance as the Kafka event records).
// Values must stay in sync with V3's chk_alert_rules_evaluation_window CHECK list.
@Getter
@RequiredArgsConstructor
public enum EvaluationWindow {
    TEN_MINUTES("10 minutes"),
    THIRTY_MINUTES("30 minutes"),
    ONE_HOUR("1 hour"),
    TWO_HOURS("2 hours"),
    THREE_HOURS("3 hours"),
    SIX_HOURS("6 hours"),
    TWELVE_HOURS("12 hours"),
    ONE_DAY("1 day");

    // Human-facing form, used by AlertRuleService.resolveName() to synthesize a rule name
    // ("1 hour / 5.000 kWh") when the client omits one. Not the Duration that drives the actual
    // InfluxDB range query - that stays usage-service's concern, per the class comment above.
    private final String label;
}
