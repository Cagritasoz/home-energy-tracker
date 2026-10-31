package com.cagritasoz.user_service.keycloak;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Clock;
import java.time.Duration;

// Builds the Keycloak admin client from app.keycloak.admin.* (KeycloakAdminProperties) and gives the circuit breaker a gauge.
//
// The pieces are static factory methods as well as beans so that a test can assemble the same client against a fake
// server with its own timeouts and breaker settings, instead of repeating this wiring.
@Configuration
@EnableConfigurationProperties(KeycloakAdminProperties.class)
public class KeycloakAdminConfig {

    @Bean
    public CircuitBreaker keycloakAdminCircuitBreaker(KeycloakAdminProperties properties) {

        KeycloakAdminProperties.CircuitBreaker breaker = properties.circuitBreaker();

        return circuitBreaker(breaker.slidingWindowSize(), breaker.minimumCalls(), breaker.failureRateThreshold(),
                breaker.waitInOpenState(), breaker.halfOpenCalls());

    }

    @Bean
    public KeycloakAdmin keycloakAdmin(CircuitBreaker keycloakAdminCircuitBreaker, KeycloakAdminProperties properties) {

        return new KeycloakAdminRestClient(
                restClient(properties.baseUrl(), properties.connectTimeout(), properties.readTimeout()),
                keycloakAdminCircuitBreaker,
                retry(properties.retry().maxAttempts(), properties.retry().waitDuration()),
                properties.realm(),
                properties.clientId(),
                properties.clientSecret(),
                properties.tokenRefreshMargin(),
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
                .waitDuration(wait) // The delay between retry attempts.
                .retryOnException(e -> e instanceof KeycloakUnavailableException unavailable && unavailable.retryable()) // retry if transient
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
