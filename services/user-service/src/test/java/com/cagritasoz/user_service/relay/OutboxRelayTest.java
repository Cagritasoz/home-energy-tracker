package com.cagritasoz.user_service.relay;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// The relay's scheduling decisions with the publisher mocked and time under the test's control: when it
// keeps draining, when it backs off, how long, and when it goes back to normal. What a batch itself does is
// OutboxBatchPublisherTest / RelayIT.
@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    private static final long INITIAL_BACKOFF_MS = 4_000;

    private static final long MAX_BACKOFF_MS = 30_000;

    @Mock
    private OutboxBatchPublisher publisher;

    private final SimpleMeterRegistry meters = new SimpleMeterRegistry();

    // The relay's clock: a test moves time by assigning, nothing sleeps.
    private long nowNanos;

    private OutboxRelay relay;

    @BeforeEach
    void setUp() {

        relay = newRelay(10);

    }

    // ---- draining ----------------------------------------------------------------------------------

    @Test
    void relay_nothingToPublish_makesOneCallAndLeavesTheBackoffAlone() {

        when(publisher.publishBatch()).thenReturn(idle());

        relay.relay();

        verify(publisher, times(1)).publishBatch();
        assertThat(relay.consecutiveFailedTicks()).isZero();

    }

    @Test
    void relay_batchNotFull_stopsAfterOneBatchAndCountsTheRows() {

        when(publisher.publishBatch()).thenReturn(published(7, false));

        relay.relay();

        verify(publisher, times(1)).publishBatch();
        assertThat(rowsPublished()).isEqualTo(7.0);

    }

    @Test
    void relay_fullBatches_keepDrainingUntilOneIsNotFull() {

        when(publisher.publishBatch()).thenReturn(published(50, true), published(50, true), published(12, false));

        relay.relay();

        verify(publisher, times(3)).publishBatch();
        assertThat(rowsPublished()).isEqualTo(112.0);

    }

    @Test
    void relay_backlogNeverEnds_stopsAtMaxBatchesPerTick() {

        OutboxRelay threeBatches = newRelay(3);
        when(publisher.publishBatch()).thenReturn(published(50, true)); // every call: full again

        threeBatches.relay();

        verify(publisher, times(3)).publishBatch(); // the next tick continues; other jobs get the thread meanwhile

    }

    // ---- backing off -------------------------------------------------------------------------------

    @Test
    void relay_tickWithoutProgress_skipsTicksUntilTheDelayHasPassed() {

        when(publisher.publishBatch()).thenReturn(failed(0));

        relay.relay();
        assertThat(relay.consecutiveFailedTicks()).isEqualTo(1);
        assertThat(batchesFailed()).isEqualTo(1.0);

        relay.relay(); // same instant: backing off, the publisher (and so the database) is not touched, publishBatch() is not called.
        verify(publisher, times(1)).publishBatch(); // Publisher called only once due to first relay() call.

        advanceMs(INITIAL_BACKOFF_MS - 1); // Advance 3 ms.
        relay.relay();
        verify(publisher, times(1)).publishBatch(); // 1 ms early: still skipped, publishBatch() is still not called.

        advanceMs(1);
        relay.relay();
        verify(publisher, times(2)).publishBatch(); // the delay has passed: a real attempt, in total publishBatch() was called only twice.

    }

    @Test
    void relay_consecutiveFailures_doubleTheDelayUpToTheCap() {

        when(publisher.publishBatch()).thenReturn(failed(0));
        long[] expectedDelaysMs = {4_000, 8_000, 16_000, 30_000, 30_000}; // 32 s and 64 s are capped at 30 s

        int calls = 0;
        for (int failure = 1; failure <= expectedDelaysMs.length; failure++) {
            relay.relay(); // a real attempt, fails
            verify(publisher, times(++calls)).publishBatch();
            assertThat(relay.consecutiveFailedTicks()).isEqualTo(failure);

            advanceMs(expectedDelaysMs[failure - 1] - 1);
            relay.relay();
            verify(publisher, times(calls)).publishBatch(); // not yet

            advanceMs(1); // the next loop iteration makes the real attempt and is not skipped.
        }

    }

    @Test
    void relay_failureAfterSomeProgress_isNotAStalledTick() {

        // 3 rows went out before the failing one: Kafka is working and the failed row is now the head of the
        // queue. Backing off here would slow down everything behind a single bad row for no reason.
        when(publisher.publishBatch()).thenReturn(failed(3));

        relay.relay();
        relay.relay(); // immediately again: no backoff

        verify(publisher, times(2)).publishBatch();
        assertThat(relay.consecutiveFailedTicks()).isZero();
        assertThat(rowsPublished()).isEqualTo(6.0); // 3 + 3
        assertThat(batchesFailed()).isEqualTo(2.0);

    }

    @Test
    void relay_progressAfterFailures_goesBackToNormalAtOnce() {

        when(publisher.publishBatch()).thenReturn(failed(0), failed(0), published(5, false), idle());

        relay.relay();
        advanceMs(INITIAL_BACKOFF_MS);
        relay.relay();
        assertThat(relay.consecutiveFailedTicks()).isEqualTo(2);

        advanceMs(2 * INITIAL_BACKOFF_MS);
        relay.relay(); // Kafka is back
        assertThat(relay.consecutiveFailedTicks()).isZero();

        relay.relay(); // no waiting after recovery
        verify(publisher, times(4)).publishBatch();

    }

    @Test
    void relay_nothingToPublishAfterFailures_keepsTheFailureCount() {

        // IDLE says nothing about Kafka's health (no rows, or another instance holds the lock).
        when(publisher.publishBatch()).thenReturn(failed(0), idle());

        relay.relay();
        advanceMs(INITIAL_BACKOFF_MS);
        relay.relay();

        assertThat(relay.consecutiveFailedTicks()).isEqualTo(1);

    }

    @Test
    void relay_interrupted_leavesTheBackoffStateAlone() {

        when(publisher.publishBatch()).thenReturn(new BatchResult(BatchResult.Outcome.INTERRUPTED, 0, false, null));

        relay.relay();

        assertThat(relay.consecutiveFailedTicks()).isZero();
        verify(publisher, times(1)).publishBatch();

    }

    @Test
    void relay_databaseError_propagatesAndIsNotAFailedTick() {

        when(publisher.publishBatch()).thenThrow(new DataAccessResourceFailureException("connection lost"));

        assertThatThrownBy(() -> relay.relay()).isInstanceOf(DataAccessResourceFailureException.class);

        // Spring's scheduler logs it; the relay itself does not wait longer, the database being down says
        // nothing about Kafka and a longer wait would only slow the recovery.
        assertThat(relay.consecutiveFailedTicks()).isZero();
        assertThatThrownBy(() -> relay.relay()).isInstanceOf(DataAccessResourceFailureException.class);
        verify(publisher, times(2)).publishBatch();

    }

    // ---- helpers -----------------------------------------------------------------------------------

    private OutboxRelay newRelay(int maxBatchesPerTick) {

        return new OutboxRelay(publisher, meters, maxBatchesPerTick, INITIAL_BACKOFF_MS, MAX_BACKOFF_MS, () -> nowNanos);

    }

    private void advanceMs(long ms) {

        nowNanos += TimeUnit.MILLISECONDS.toNanos(ms);

    }

    private double rowsPublished() {

        return meters.counter("outbox.rows.published").count();

    }

    private double batchesFailed() {

        return meters.counter("outbox.batches.failed").count();

    }

    private static BatchResult idle() {

        return BatchResult.idle();

    }

    private static BatchResult published(int rows, boolean full) {

        return new BatchResult(BatchResult.Outcome.PUBLISHED, rows, full, null);

    }

    private static BatchResult failed(int publishedBeforeTheFailure) {

        return new BatchResult(BatchResult.Outcome.FAILED, publishedBeforeTheFailure, false, new RuntimeException("send failed"));

    }
}
