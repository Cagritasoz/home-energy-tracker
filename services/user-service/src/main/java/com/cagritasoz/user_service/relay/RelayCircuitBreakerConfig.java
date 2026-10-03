package com.cagritasoz.user_service.relay;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;

@Slf4j
@Configuration
public class RelayCircuitBreakerConfig {

    @Bean
    CircuitBreaker relayProducerCircuitBreaker(
            @Value("${app.outbox.relay.circuit-breaker.window-size}") int windowSize,
            @Value("${app.outbox.relay.circuit-breaker.failure-rate-threshold}") float failureRateThreshold,
            @Value("${app.outbox.relay.circuit-breaker.wait-in-open-state-ms}") long waitInOpenStateMs,
            @Value("${app.outbox.relay.circuit-breaker.half-open-calls}") int halfOpenCalls) {

        // "Look at the last 5 completed calls. Once I've seen at least 5 calls, if 100% of them failed, OPEN the circuit.
        // Stay OPEN for 30 seconds. Then allow exactly 1 test call (HALF_OPEN). If that succeeds, close the circuit; if it fails, go back to OPEN."
        // Going back to OPEN state from HALF_OPEN state waits for 30 seconds again.
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(windowSize)
                .minimumNumberOfCalls(windowSize)
                .failureRateThreshold(failureRateThreshold)
                .waitDurationInOpenState(Duration.ofMillis(waitInOpenStateMs))
                .permittedNumberOfCallsInHalfOpenState(halfOpenCalls)
                .build();

        CircuitBreaker circuitBreaker = CircuitBreaker.of("relayProducer", config);

        circuitBreaker.getEventPublisher()
                .onStateTransition(event -> log.warn("Outbox relay circuit breaker: {}", event.getStateTransition()));

        return circuitBreaker;

    }
}
