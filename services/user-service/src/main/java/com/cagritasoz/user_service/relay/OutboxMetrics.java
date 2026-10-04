package com.cagritasoz.user_service.relay;

import com.cagritasoz.user_service.repository.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

// Micrometer is a metrics instrumentation library for Java applications.
@Component
public class OutboxMetrics implements MeterBinder {

    private final OutboxRepository outboxRepository;

    private final OutboxRelay outboxRelay;

    public OutboxMetrics(OutboxRepository outboxRepository, OutboxRelay outboxRelay) {
        this.outboxRepository = outboxRepository;
        this.outboxRelay = outboxRelay;
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

        // 0 = healthy; N = the last N ticks in a row published nothing and the relay is backing off. A plain
        // number that only goes back to 0 on progress, so "> 0 for 2 minutes" is a safe alert rule (a
        // breaker-state gauge flaps between OPEN and HALF_OPEN while Kafka is still down).
        Gauge.builder("outbox.relay.consecutive.failed.ticks", outboxRelay, OutboxRelay::consecutiveFailedTicks)
                .register(registry);

    }
}
