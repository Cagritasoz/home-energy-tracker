package com.cagritasoz.user_service.keycloak;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

// app.keycloak.admin.* (explained in application.yaml).
@Validated
@ConfigurationProperties(prefix = "app.keycloak.admin", ignoreUnknownFields = false)
public record KeycloakAdminProperties(
        @NotBlank String baseUrl,
        @NotBlank String realm,
        @NotBlank String clientId,
        // Binding, unlike @Value, keeps a ${...} it cannot resolve as plain text: with the environment variable missing the
        // secret would be "${USER_SERVICE_ADMIN_CLIENT_SECRET}" and the first token request would fail instead of the start-up.
        @NotBlank @Pattern(regexp = "^(?!\\$\\{).*", message = "is an unresolved placeholder: is USER_SERVICE_ADMIN_CLIENT_SECRET set?") String clientSecret,
        @NotNull @DurationMin(nanos = 1, message = "must be positive") Duration connectTimeout,
        @NotNull @DurationMin(nanos = 1, message = "must be positive") Duration readTimeout,
        @NotNull @DurationMin(nanos = 0, message = "must not be negative") Duration tokenRefreshMargin,
        @NotNull @Valid Retry retry,
        @NotNull @Valid CircuitBreaker circuitBreaker) {

    public record Retry(@Positive int maxAttempts,
                        @NotNull @DurationMin(nanos = 0, message = "must not be negative") Duration waitDuration) {
    }

    public record CircuitBreaker(@Positive int slidingWindowSize,
                                 @Positive int minimumCalls,
                                 @DecimalMin(value = "0", inclusive = false) @DecimalMax("100") float failureRateThreshold,
                                 @NotNull @DurationMin(nanos = 1, message = "must be positive") Duration waitInOpenState,
                                 @Positive int halfOpenCalls) {
    }
}
