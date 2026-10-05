package com.cagritasoz.user_service.relay;

import com.cagritasoz.user_service.repository.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

// Micrometer is a metrics instrumentation library for Java applications.
// Implementing MeterBinder means "I have a group of custom meters that I want to register with Micrometer."
// We must implement bindTo method which takes MeterRegistry as parameter.
@Component
public class OutboxMetrics implements MeterBinder {

    private final OutboxRepository outboxRepository;

    private final OutboxRelay outboxRelay;

    public OutboxMetrics(OutboxRepository outboxRepository, OutboxRelay outboxRelay) {
        this.outboxRepository = outboxRepository;
        this.outboxRelay = outboxRelay;
    }

    // MeterRegistry is the application's central collection of registered metrics (gauges, counters etc.)
    // It knows about these meters and how to expose their current values (We tell it how to get the value via lambdas or calling the Meter objects own methods)
    // Spring Boot creates/configures one for the application and actuator dependency includes it.
    // In a Spring Boot + Prometheus setup, Prometheus periodically scrapes the application's metrics endpoint.
    @Override
    public void bindTo(@NonNull MeterRegistry registry) {

        // A gauge represents a current value, this value may go up or down.
        // Metric name is "outbox.pending.rows"
        // outboxRepository represents the object which the gauge's instantaneous value is determined from.
        // Lambda is a function that yields a double value for the gauge, based on the state of obj.
        // This essentially means Create a gauge named outbox.pending.rows, associated with this repository,
        // and when Micrometer needs the current value, call this method on the repository.
        Gauge.builder("outbox.pending.rows", outboxRepository, OutboxRepository::countByPublishedAtIsNullAndParkedFalse)
                .register(registry);

        Gauge.builder("outbox.oldest.pending.age.seconds", outboxRepository, OutboxRepository::oldestPendingAgeSeconds)
                .baseUnit("seconds")
                .register(registry);

        Gauge.builder("outbox.parked.rows", outboxRepository, OutboxRepository::countByParkedTrue)
                .register(registry);

        // 0 = healthy; N = the last N ticks in a row published nothing and the relay is backing off. A plain
        // number that only goes back to 0 on progress, so "> 0 for 2 minutes" is a safe alert rule (a
        // breaker-state gauge flaps between OPEN and HALF_OPEN while Kafka is still down).
        Gauge.builder("outbox.relay.consecutive.failed.ticks", outboxRelay, OutboxRelay::consecutiveFailedTicks)
                .register(registry);

    }
}
