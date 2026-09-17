package com.cagritasoz.user_service.model;

// The exact wire value published as each Kafka event's type (e.g. "USER_DELETED"). Values must
// stay in sync with V4's chk_outbox_events_event_type CHECK list - no compiler link between a raw
// SQL CHECK and a Java enum, so a new event type needs both updated together by hand.
public enum OutboxEventType {
    USER_CREATED,
    USER_UPDATED,
    USER_DELETED
}
