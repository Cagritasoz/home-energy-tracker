package com.cagritasoz.user_service.dto;

import com.cagritasoz.user_service.model.AlertScope;
import com.cagritasoz.user_service.model.EvaluationWindow;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;

import java.math.BigDecimal;
import java.time.Instant;

@Builder
@JsonPropertyOrder({"id", "userId", "name", "evaluationWindow", "thresholdKwh", "scope", "enabled", "createdAt", "updatedAt"})
public record AlertRuleResponse(

        Long id,

        Long userId,

        String name,

        EvaluationWindow evaluationWindow,

        BigDecimal thresholdKwh,

        AlertScope scope,

        Boolean enabled,

        Instant createdAt,

        Instant updatedAt

) {
}
