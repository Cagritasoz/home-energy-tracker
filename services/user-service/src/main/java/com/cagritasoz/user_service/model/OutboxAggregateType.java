package com.cagritasoz.user_service.model;

// Which table an outbox row's aggregate_id refers to. "Outbox" prefix, not bare AggregateType -
// names the exact concept, not a generic one. USER is the only value now that alert rules have
// moved out of user-service entirely (their own service in the new design) - kept as an enum
// rather than dropped in favor of a hardcoded constant since the redesigned outbox (see V5) may
// still need to distinguish aggregate types once this service owns more than one again.
public enum OutboxAggregateType {
    USER
}
