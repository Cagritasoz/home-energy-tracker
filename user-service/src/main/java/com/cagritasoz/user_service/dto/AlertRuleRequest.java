package com.cagritasoz.user_service.dto;

import com.cagritasoz.user_service.model.EvaluationWindow;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import lombok.Builder;

import java.math.BigDecimal;

@Builder
public record AlertRuleRequest(

        // May be blank or null - the service derives a name ("1 hour / 5.0 kWh") when it is.
        @Size(max = 100, message = "must be at most 100 characters")
        String name,

        @NotNull(message = "must not be null")
        EvaluationWindow evaluationWindow,

        @NotNull(message = "must not be null")
        @Positive(message = "must be positive")
        @Digits(integer = 7, fraction = 3, message = "must have at most 7 integer digits and 3 decimal places") // Matches NUMERIC(10,3)
        BigDecimal thresholdKwh,

        // Omitting AlertScope as there is only one scope feature so far, and it defaults to ALL_DEVICES. If more are added AlertScope field should be included here.

        Boolean enabled
) {
}
