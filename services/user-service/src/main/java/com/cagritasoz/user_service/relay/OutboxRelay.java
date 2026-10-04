package com.cagritasoz.user_service.relay;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;

// The scheduled side of the relay: decides WHEN to publish. OutboxBatchPublisher decides WHAT (it holds the
// lock and the transaction).
//
// Why a backoff and not a circuit breaker: this relay pulls from Postgres with one scheduler thread, so a
// slow or dead Kafka cannot make threads pile up, and the outbox table itself is the buffer that absorbs an
// outage. Kafka is not at risk either - the producer already backs off its own reconnects and retries, and the
// relay sends at most one batch of 50 records per tick. What a failing tick does cost is the relay's own
// resources: a database connection and the advisory lock for up to send-timeout-ms, an UPDATE of
// attempts/last_error, and a WARN line - every ~7 s, forever, for an outage or a poison row. So after a tick
// that made no progress the relay simply waits longer before the next one, doubling up to a cap, and goes back
// to normal as soon as a tick makes progress. No half-open probes, no failure-rate window.
@Slf4j
@Component
public class OutboxRelay {

    private final OutboxBatchPublisher batchPublisher;

    private final int maxBatchesPerTick;

    private final long initialBackoffNanos;

    private final long maxBackoffNanos;

    // Injectable so a test can move time without sleeping; production uses System.nanoTime.
    private final LongSupplier nanoClock;

    // Two counters with different units, named so nobody divides one by the other:
    //  - outbox.rows.published: rows acknowledged by Kafka and marked published (a counter of ROWS);
    //  - outbox.batches.failed: batches that stopped on a send error (a counter of BATCHES). The rows behind the
    //    failed one are neither published nor failed - they stay pending and are sent again - and a failure that
    //    hits a whole partition still counts once, so this is "how often the relay hit an error", not a count
    //    of failed rows.
    private final Counter rowsPublishedCounter;

    private final Counter batchesFailedCounter;

    // Written only by the scheduler thread; volatile because the metrics gauge reads it from another thread.
    private volatile int consecutiveFailedTicks = 0;

    // A System.nanoTime() reading, which may be negative: only ever compared as a difference, and initialized
    // from the clock itself so "not backing off" is true from the start.
    // "Don't attempt another relay until this point on the stopwatch."
    // Wait time = 2 secs = 2,000,000,000 ns. backoffUntilNanos = System.nanoTime() (current nano time) + 2,000,000,000
    private volatile long backoffUntilNanos;

    // Spring calls this constructor which delegates to the package private constructor.
    // Clock is supplied as System.nanoTime() and tests can supply their own clock.
    @Autowired
    public OutboxRelay(OutboxBatchPublisher batchPublisher,
                       MeterRegistry meterRegistry,
                       @Value("${app.outbox.relay.max-batches-per-tick}") int maxBatchesPerTick,
                       @Value("${app.outbox.relay.backoff.initial-ms}") long initialBackoffMs,
                       @Value("${app.outbox.relay.backoff.max-ms}") long maxBackoffMs) {
        this(batchPublisher, meterRegistry, maxBatchesPerTick, initialBackoffMs, maxBackoffMs, System::nanoTime);
    }

    OutboxRelay(OutboxBatchPublisher batchPublisher,
                MeterRegistry meterRegistry,
                int maxBatchesPerTick,
                long initialBackoffMs,
                long maxBackoffMs,
                LongSupplier nanoClock) {
        this.batchPublisher = batchPublisher;
        this.maxBatchesPerTick = maxBatchesPerTick;
        this.initialBackoffNanos = TimeUnit.MILLISECONDS.toNanos(initialBackoffMs);
        this.maxBackoffNanos = TimeUnit.MILLISECONDS.toNanos(maxBackoffMs);
        this.nanoClock = nanoClock;
        this.backoffUntilNanos = nanoClock.getAsLong();
        this.rowsPublishedCounter = meterRegistry.counter("outbox.rows.published");
        this.batchesFailedCounter = meterRegistry.counter("outbox.batches.failed");
    }

    // Failed ticks in a row without any progress; 0 = healthy. The alarm signal for "the relay is backing off".
    public int consecutiveFailedTicks() {

        return consecutiveFailedTicks;

    }

    @Scheduled(fixedDelayString = "${app.outbox.relay.interval-ms}")
    public void relay() {

        // Still backing off from the last failed tick: do nothing, not even open a transaction.
        // Equivalent to now < backoffUntilNanos.
        if (nanoClock.getAsLong() - backoffUntilNanos < 0) {
            return;
        }

        for (int batch = 0; batch < maxBatchesPerTick; batch++) {

            // A database error is not counted as a failed tick: it says nothing about Kafka or the rows, and
            // delaying the next tick would only slow the recovery. The exception reaches Spring's scheduler,
            // which logs it at ERROR with the stack trace and keeps the schedule alive.
            BatchResult result = batchPublisher.publishBatch();

            rowsPublishedCounter.increment(result.published());

            switch (result.outcome()) {
                case IDLE, INTERRUPTED -> {
                    // Nothing to publish, another instance holds the lock, or shutdown: no information about
                    // Kafka's health, so the backoff state stays as it is.
                    return;
                }
                case FAILED -> {
                    batchesFailedCounter.increment();

                    if (result.published() > 0) {
                        // The batch failed part-way but the rows before the failure went out: Kafka is working,
                        // the failed row is the head of the queue now. Not a stalled tick, so no backoff yet; if
                        // that row keeps failing, the next tick publishes nothing and starts the backoff.
                        recovered();
                    }
                    else {
                        backOff();
                    }
                    return;
                }
                case PUBLISHED -> {
                    recovered();
                    if (!result.full()) {
                        return; // No more batches to send, break the loop.
                    }
                }
            }
        }
    }

    // "This entire attempt made zero progress. Don't hammer the database/Kafka again on every scheduler tick."
    // Backoff protects the application/database from repeatedly attempting work that currently cannot make progress.
    // Backoff does not handle kafka communication, kafka producer settings handle kafka retries.
    // Exponential backoff up to max of 30 secs.
    private void backOff() {

        int failedTicks = consecutiveFailedTicks + 1;
        consecutiveFailedTicks = failedTicks;

        // initial * 2^(failedTicks - 1), capped. The shift is bounded first so it cannot overflow.
        // Math.min guard prevents the shift from becoming absurdly large.
        // This determines how many times the initial backoff will be doubled.
        // failedTicks = 1, doublings = 0, no double operations, use initial backoff | failed ticks = 2, doublings = 1, double it once...
        int doublings = Math.min(failedTicks - 1, 20);

        // initialBackoffNanos << doublings is a bit shift. "a << 1 = a x 2", "a << 2 = a x 2 x 2", "a << 3 = a x 2 x 2 x 2"...
        // doublings = 0, delayNanos = initial backoff | doublings = 1, delayNanos = initial backoff x 2...
        long delayNanos = Math.min(maxBackoffNanos, initialBackoffNanos << doublings);
        if (delayNanos <= 0) { // the shift overflowed for a huge initial value and is negative.
            delayNanos = maxBackoffNanos;
        }
        backoffUntilNanos = nanoClock.getAsLong() + delayNanos;

        log.warn("Outbox relay: {} tick(s) in a row without progress, next attempt in {} ms",
                failedTicks, TimeUnit.NANOSECONDS.toMillis(delayNanos));

    }

    private void recovered() {

        if (consecutiveFailedTicks > 0) {
            log.info("Outbox relay: publishing again after {} failed tick(s)", consecutiveFailedTicks);
        }
        consecutiveFailedTicks = 0;
        backoffUntilNanos = nanoClock.getAsLong(); // no waiting

    }
}
