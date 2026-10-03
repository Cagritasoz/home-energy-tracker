package com.cagritasoz.user_service.relay;

import com.cagritasoz.user_service.repository.OutboxRepository;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;

@Component
public class OutboxMetrics implements MeterBinder {

    private final OutboxRepository outboxRepository;

    private final CircuitBreaker circuitBreaker;

    public OutboxMetrics(OutboxRepository outboxRepository,
                         @Qualifier("relayProducerCircuitBreaker") CircuitBreaker circuitBreaker) {
        this.outboxRepository = outboxRepository;
        this.circuitBreaker = circuitBreaker;
    }

    @Override
    public void bindTo(@NonNull MeterRegistry registry) {

        Gauge.builder("outbox.pending.rows", outboxRepository, OutboxRepository::countByPublishedAtIsNullAndParkedFalse)
                .register(registry);

        Gauge.builder("outbox.oldest.pending.age.seconds", outboxRepository, OutboxRepository::oldestPendingAgeSeconds)
                .baseUnit("seconds")
                .register(registry);

        Gauge.builder("outbox.parked.rows", outboxRepository, OutboxRepository::countByParkedTrue)
                .register(registry);

        Gauge.builder("outbox.relay.circuit.breaker.open", circuitBreaker, breaker -> isOpen(breaker) ? 1 : 0)
                .register(registry);

    }

    private static boolean isOpen(CircuitBreaker breaker) {

        CircuitBreaker.State state = breaker.getState();

        return state == CircuitBreaker.State.OPEN || state == CircuitBreaker.State.FORCED_OPEN;

    }
}
