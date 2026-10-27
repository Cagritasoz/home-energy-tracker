package com.cagritasoz.user_service.keycloak;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

// Builds the Keycloak admin client from app.keycloak.admin.* and gives the circuit breaker a gauge.
//
// The pieces are static factory methods as well as beans so that a test can assemble the same client against a fake
// server with its own timeouts and breaker settings, instead of repeating this wiring.
@Configuration
public class KeycloakAdminConfig {

    @Bean
    public CircuitBreaker keycloakAdminCircuitBreaker(
            @Value("${app.keycloak.admin.circuit-breaker.sliding-window-size}") int slidingWindowSize,
            @Value("${app.keycloak.admin.circuit-breaker.minimum-calls}") int minimumCalls,
            @Value("${app.keycloak.admin.circuit-breaker.failure-rate-threshold}") float failureRateThreshold,
            @Value("${app.keycloak.admin.circuit-breaker.wait-in-open-state}") Duration waitInOpenState,
            @Value("${app.keycloak.admin.circuit-breaker.half-open-calls}") int halfOpenCalls) {

        return circuitBreaker(slidingWindowSize, minimumCalls, failureRateThreshold, waitInOpenState, halfOpenCalls);

    }

    @Bean
    public KeycloakAdmin keycloakAdmin(
            CircuitBreaker keycloakAdminCircuitBreaker,
            @Value("${app.keycloak.admin.base-url}") String baseUrl,
            @Value("${app.keycloak.admin.realm}") String realm,
            @Value("${app.keycloak.admin.client-id}") String clientId,
            @Value("${app.keycloak.admin.client-secret}") String clientSecret,
            @Value("${app.keycloak.admin.connect-timeout}") Duration connectTimeout,
            @Value("${app.keycloak.admin.read-timeout}") Duration readTimeout,
            @Value("${app.keycloak.admin.token-refresh-margin}") Duration tokenRefreshMargin,
            @Value("${app.keycloak.admin.retry.max-attempts}") int retryMaxAttempts,
            @Value("${app.keycloak.admin.retry.wait}") Duration retryWait) {

        return new KeycloakAdminRestClient(
                restClient(baseUrl, connectTimeout, readTimeout),
                keycloakAdminCircuitBreaker,
                retry(retryMaxAttempts, retryWait),
                realm,
                clientId,
                clientSecret,
                tokenRefreshMargin,
                Clock.systemUTC());

    }

    // 0 = closed (normal), 1 = half open (probing), 2 = open (refusing calls). Anything else (disabled, forced open)
    // is never configured here. Like the outbox gauges, it reads the live object on every scrape.
    @Bean
    public MeterBinder keycloakAdminMetrics(CircuitBreaker keycloakAdminCircuitBreaker) {

        return registry -> Gauge.builder("keycloak.admin.circuit.state", keycloakAdminCircuitBreaker, breaker -> switch (breaker.getState()) {

                    case CLOSED -> 0;
                    case HALF_OPEN -> 1;
                    default -> 2;

                })
                .description("State of the circuit breaker in front of Keycloak's admin API: 0 closed, 1 half open, 2 open")
                .register(registry);

    }

    public static CircuitBreaker circuitBreaker(int slidingWindowSize, int minimumCalls, float failureRateThreshold,
                                                Duration waitInOpenState, int halfOpenCalls) {

        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(slidingWindowSize)
                .minimumNumberOfCalls(minimumCalls)
                .failureRateThreshold(failureRateThreshold)
                .waitDurationInOpenState(waitInOpenState)
                .permittedNumberOfCallsInHalfOpenState(halfOpenCalls)
                .build();

        return CircuitBreaker.of("keycloak-admin", config);

    }

    // Retries only what KeycloakUnavailableException says may be transient. The circuit breaker's refusal is not
    // retried: asking again at once would just be refused again.
    public static Retry retry(int maxAttempts, Duration wait) {

        RetryConfig config = RetryConfig.custom()
                .maxAttempts(maxAttempts)
                .waitDuration(wait)
                .retryOnException(e -> e instanceof KeycloakUnavailableException unavailable && unavailable.retryable())
                .build();

        return Retry.of("keycloak-admin", config);

    }

    // The JDK HttpClient, forced to HTTP/1.1 (no h2c upgrade attempt on a plain http URL). The read timeout covers the
    // wait for the response, so a Keycloak that accepts the connection and then hangs is cut off too.
    public static RestClient restClient(String baseUrl, Duration connectTimeout, Duration readTimeout) {

        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(connectTimeout)
                .build();

        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(readTimeout);

        return RestClient.builder()
                .baseUrl(baseUrl)
                .requestFactory(requestFactory)
                .build();

    }
}
