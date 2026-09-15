package com.cagritasoz.user_service.model;

// Which table an outbox row's aggregate_id refers to. Values must stay in sync with V4's
// chk_outbox_events_aggregate_type CHECK list. "Outbox" prefix, not bare AggregateType - same
// reasoning as AlertScope over bare Scope: names the exact concept, not a generic one.
public enum OutboxAggregateType {
    USER,
    ALERT_RULE
}
