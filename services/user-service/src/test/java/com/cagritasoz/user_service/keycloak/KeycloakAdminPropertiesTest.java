package com.cagritasoz.user_service.keycloak;

import com.cagritasoz.user_service.testsupport.config.ConfigAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

// app.keycloak.admin.* as the application binds it, from the real application.yaml with one setting changed. The secret
// is not in the file (it comes from the environment), so every test supplies one.
class KeycloakAdminPropertiesTest {

    @EnableConfigurationProperties(KeycloakAdminProperties.class)
    static class Enable {
    }

    private final ApplicationContextRunner runner = ConfigAssertions.runnerFor(Enable.class)
            .withPropertyValues("app.keycloak.admin.client-secret=test-secret");

    @Test
    void applicationYaml_bindsEverySetting() {

        runner.run(context -> {

            KeycloakAdminProperties properties = context.getBean(KeycloakAdminProperties.class);

            assertThat(properties.baseUrl()).isEqualTo("http://localhost:8080");
            assertThat(properties.realm()).isEqualTo("energy-tracker");
            assertThat(properties.clientId()).isEqualTo("user-service-admin");
            assertThat(properties.clientSecret()).isEqualTo("test-secret");
            assertThat(properties.connectTimeout()).isEqualTo(Duration.ofSeconds(1));
            assertThat(properties.readTimeout()).isEqualTo(Duration.ofSeconds(3));
            assertThat(properties.tokenRefreshMargin()).isEqualTo(Duration.ofSeconds(30));
            assertThat(properties.retry()).isEqualTo(new KeycloakAdminProperties.Retry(2, Duration.ofMillis(200)));
            assertThat(properties.circuitBreaker())
                    .isEqualTo(new KeycloakAdminProperties.CircuitBreaker(10, 5, 50f, Duration.ofSeconds(30), 2));

        });

    }

    @Test
    void clientSecret_blank_stopsTheStartUp() {

        ConfigAssertions.assertRefused(runner, "app.keycloak.admin.client-secret= ", "NotBlank");

    }

    // Boot leaves a placeholder it cannot resolve as it is, so without the environment variable the secret would be
    // the placeholder's own text, and every token request would fail later instead of the start-up.
    @Test
    void clientSecret_unresolvedPlaceholder_stopsTheStartUp() {

        // A variable that exists nowhere, so the test does not depend on the environment it runs in.
        ConfigAssertions.assertRefused(runner, "app.keycloak.admin.client-secret=${NO_SUCH_VARIABLE_ANYWHERE}",
                "unresolved placeholder");

    }

    @ParameterizedTest
    @ValueSource(strings = {"base-url", "realm", "client-id"})
    void blankIdentity_stopsTheStartUp(String key) {

        ConfigAssertions.assertRefused(runner, "app.keycloak.admin." + key + "= ", "NotBlank");

    }

    @Test
    void unknownKey_stopsTheStartUp() {

        ConfigAssertions.assertUnknownKeyRefused(runner, "app.keycloak.admin.read-timeuot=1s");

    }

    @ParameterizedTest
    @ValueSource(strings = {"connect-timeout", "read-timeout", "circuit-breaker.wait-in-open-state"})
    void timeoutNotPositive_stopsTheStartUp(String key) {

        ConfigAssertions.assertRefused(runner, "app.keycloak.admin." + key + "=0s", "must be positive");
        ConfigAssertions.assertRefused(runner, "app.keycloak.admin." + key + "=-1s", "must be positive");

    }

    @Test
    void tokenRefreshMargin_negative_stopsTheStartUp() {

        ConfigAssertions.assertRefused(runner, "app.keycloak.admin.token-refresh-margin=-1s", "must not be negative");
        ConfigAssertions.assertAccepted(runner, "app.keycloak.admin.token-refresh-margin=0s");

    }

    @Test
    void retry_attemptsBelowOne_stopsTheStartUp() {

        ConfigAssertions.assertRefused(runner, "app.keycloak.admin.retry.max-attempts=0", "Positive");
        ConfigAssertions.assertAccepted(runner, "app.keycloak.admin.retry.max-attempts=1"); // 1 = no retry

    }

    @Test
    void retry_negativeWait_stopsTheStartUp() {

        ConfigAssertions.assertRefused(runner, "app.keycloak.admin.retry.wait-duration=-1ms", "must not be negative");
        ConfigAssertions.assertAccepted(runner, "app.keycloak.admin.retry.wait-duration=0ms");

    }

    @ParameterizedTest
    @ValueSource(strings = {"sliding-window-size", "minimum-calls", "half-open-calls"})
    void circuitBreakerCounts_belowOne_stopsTheStartUp(String key) {

        ConfigAssertions.assertRefused(runner, "app.keycloak.admin.circuit-breaker." + key + "=0", "Positive");

    }

    // A percentage Resilience4j accepts from just above 0 up to 100.
    @Test
    void circuitBreaker_failureRateThreshold_isAPercentageAboveZero() {

        ConfigAssertions.assertRefused(runner, "app.keycloak.admin.circuit-breaker.failure-rate-threshold=0", "DecimalMin");
        ConfigAssertions.assertRefused(runner, "app.keycloak.admin.circuit-breaker.failure-rate-threshold=100.5", "DecimalMax");
        ConfigAssertions.assertAccepted(runner, "app.keycloak.admin.circuit-breaker.failure-rate-threshold=0.5");
        ConfigAssertions.assertAccepted(runner, "app.keycloak.admin.circuit-breaker.failure-rate-threshold=100");

    }
}
