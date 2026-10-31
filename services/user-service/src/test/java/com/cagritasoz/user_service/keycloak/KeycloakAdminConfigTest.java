package com.cagritasoz.user_service.keycloak;

import com.cagritasoz.user_service.testsupport.config.ConfigFixtures;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

// The gauge that makes the circuit breaker visible, and the breaker settings that come out of the configuration. The
// client's behavior around them is in KeycloakAdminRestClientTest.
class KeycloakAdminConfigTest {

    private final KeycloakAdminConfig config = new KeycloakAdminConfig();

    @Test
    void circuitStateGauge_followsTheBreaker() {

        CircuitBreaker breaker = KeycloakAdminConfig.circuitBreaker(10, 5, 50f, Duration.ofSeconds(30), 2);
        MeterRegistry registry = new SimpleMeterRegistry();
        MeterBinder binder = config.keycloakAdminMetrics(breaker);

        binder.bindTo(registry);

        double closed = registry.get("keycloak.admin.circuit.state").gauge().value();
        breaker.transitionToOpenState();
        double open = registry.get("keycloak.admin.circuit.state").gauge().value();
        breaker.transitionToHalfOpenState();
        double halfOpen = registry.get("keycloak.admin.circuit.state").gauge().value();
        breaker.transitionToClosedState();
        double closedAgain = registry.get("keycloak.admin.circuit.state").gauge().value();

        assertThat(closed).isZero();
        assertThat(open).isEqualTo(2);
        assertThat(halfOpen).isEqualTo(1);
        assertThat(closedAgain).isZero();

    }

    @Test
    void circuitBreaker_takesItsSettingsFromTheArguments() {

        CircuitBreaker breaker = KeycloakAdminConfig.circuitBreaker(8, 3, 60f, Duration.ofSeconds(45), 4);

        assertThat(breaker.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(8);
        assertThat(breaker.getCircuitBreakerConfig().getMinimumNumberOfCalls()).isEqualTo(3);
        assertThat(breaker.getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(60f);
        assertThat(breaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(45_000L);
        assertThat(breaker.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(4);
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

    }

    // Five numbers of one type, so a swapped pair compiles: every value is different, and each must land in its place.
    @Test
    void keycloakAdminCircuitBreaker_takesEverySettingFromTheProperties() {

        KeycloakAdminProperties properties = ConfigFixtures.keycloakAdmin("client-secret=test-secret",
                "circuit-breaker.sliding-window-size=8", "circuit-breaker.minimum-calls=3",
                "circuit-breaker.failure-rate-threshold=60", "circuit-breaker.wait-in-open-state=45s",
                "circuit-breaker.half-open-calls=4");

        CircuitBreaker breaker = config.keycloakAdminCircuitBreaker(properties);

        assertThat(breaker.getCircuitBreakerConfig().getSlidingWindowSize()).isEqualTo(8);
        assertThat(breaker.getCircuitBreakerConfig().getMinimumNumberOfCalls()).isEqualTo(3);
        assertThat(breaker.getCircuitBreakerConfig().getFailureRateThreshold()).isEqualTo(60f);
        assertThat(breaker.getCircuitBreakerConfig().getWaitIntervalFunctionInOpenState().apply(1)).isEqualTo(45_000L);
        assertThat(breaker.getCircuitBreakerConfig().getPermittedNumberOfCallsInHalfOpenState()).isEqualTo(4);

    }

    @Test
    void retry_retriesOnlyWhatTheExceptionSaysMayBeTransient() {

        var retry = KeycloakAdminConfig.retry(3, Duration.ofMillis(5));
        var predicate = retry.getRetryConfig().getExceptionPredicate();

        assertThat(retry.getRetryConfig().getMaxAttempts()).isEqualTo(3);
        assertThat(predicate.test(new KeycloakUnavailableException("timeout", true))).isTrue(); // retriable = true
        assertThat(predicate.test(new KeycloakUnavailableException("forbidden", false))).isFalse(); // not retriable = false
        assertThat(predicate.test(new KeycloakCircuitOpenException(null))).isFalse();
        assertThat(predicate.test(new IllegalStateException("a bug"))).isFalse();

    }
}
