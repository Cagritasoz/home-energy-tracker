package com.cagritasoz.user_service.relay;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

@Component
public class OutboxRelay {

    private final OutboxBatchPublisher batchPublisher;

    private final CircuitBreaker circuitBreaker;

    private final int maxBatchesPerTick;

    private final Counter publishedCounter;

    private final Counter failedCounter;

    public OutboxRelay(OutboxBatchPublisher batchPublisher,
                       // Qualifier because there might exist two CircuitBreaker beans.
                       @Qualifier("relayProducerCircuitBreaker") CircuitBreaker circuitBreaker,
                       MeterRegistry meterRegistry,
                       @Value("${app.outbox.relay.max-batches-per-tick}") int maxBatchesPerTick) {
        this.batchPublisher = batchPublisher;
        this.circuitBreaker = circuitBreaker;
        this.maxBatchesPerTick = maxBatchesPerTick;
        this.publishedCounter = meterRegistry.counter("outbox.publish", "result", "success");
        this.failedCounter = meterRegistry.counter("outbox.publish", "result", "failure");
    }

    @Scheduled(fixedDelayString = "${app.outbox.relay.interval-ms}")
    public void relay() {

        for (int batch = 0; batch < maxBatchesPerTick; batch++) {

            // tryAcquirePermission() means "Am I allowed to make a call?"
            // Return false for OPEN state, true for CLOSED and HALF_OPEN states.
            // In HALF_OPEN state tryAcquirePermission() probes with only one call. (One call is a property of the config).
            if (!circuitBreaker.tryAcquirePermission()) {
                return;
            }

            BatchResult result;
            long start = System.nanoTime();

            try {
                // publishBatch() is the call whose outcome we are reporting to the circuit breaker via the switch(result.outcome()) below.
                result = batchPublisher.publishBatch();
            }

            // TODO: Should DB outages be handled?
            catch (RuntimeException e) { // Handles database thrown exceptions.

                // "I acquired permission, but I did not actually make a call, so don't count this as success or failure."
                circuitBreaker.releasePermission();

                // Exception reaches Spring's scheduler.
                // Spring logs Unexpected error occurred in scheduled task at ERROR with the stack trace and keeps the schedule alive.
                // The next tick runs after fixedDelay, db outage cases, a lot of noise is to be expected.
                throw e;
            }

            long elapsed = System.nanoTime() - start;
            publishedCounter.increment(result.published());

            switch (result.outcome()) {
                case IDLE, INTERRUPTED -> {
                    circuitBreaker.releasePermission();
                    return;
                }
                case FAILED -> {
                    failedCounter.increment();

                    // onError() -> "The protected call failed. It took this long, and this is the exception responsible for the failure."
                    // The breaker records the error outcome in its sliding window.
                    circuitBreaker.onError(elapsed, TimeUnit.NANOSECONDS, result.failure());
                    return;
                }
                case PUBLISHED -> {

                    // onSuccess() means "The protected call completed successfully, and it took this long".
                    // The breaker records the success outcome in its sliding window.
                    circuitBreaker.onSuccess(elapsed, TimeUnit.NANOSECONDS);
                    if (!result.full()) {
                        return; // No more batches to send, break the loop.
                    }
                }
            }
        }
    }
}
