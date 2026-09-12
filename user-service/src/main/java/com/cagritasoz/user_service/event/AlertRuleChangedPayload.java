package com.cagritasoz.user_service.event;

import com.cagritasoz.user_service.model.AlertScope;
import com.cagritasoz.user_service.model.EvaluationWindow;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;

// Outbox payload for both ALERT_RULE_CREATED and ALERT_RULE_UPDATED - same reasoning as
// UserChangedPayload: one shape, eventType on the outbox row says which happened.
@Builder
public record AlertRuleChangedPayload(
        Long ruleId,
        Long userId,
        String name,
        EvaluationWindow evaluationWindow,
        BigDecimal thresholdKwh,
        AlertScope scope,
        Boolean enabled,

        @JsonFormat(shape = JsonFormat.Shape.STRING)
        Instant occurredAt
) {
}
