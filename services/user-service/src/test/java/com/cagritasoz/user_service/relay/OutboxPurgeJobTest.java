package com.cagritasoz.user_service.relay;

import com.cagritasoz.user_service.repository.OutboxRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// The purge job's loop with the repository mocked: when it keeps deleting, when it stops, and what it
// reports. What the delete statement itself does is OutboxRepositoryIT / OutboxPurgeJobIT.
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class OutboxPurgeJobTest {

    private static final int RETENTION_DAYS = 7;

    private static final int BATCH_SIZE = 3;

    @Mock
    private OutboxRepository outboxRepository;

    private OutboxPurgeJob purgeJob;

    @BeforeEach
    void setUp() {

        purgeJob = new OutboxPurgeJob(outboxRepository, RETENTION_DAYS, BATCH_SIZE);

    }

    @Test
    void purge_nothingToDelete_makesOneCallAndStaysQuiet(CapturedOutput output) {

        when(outboxRepository.deletePublishedOlderThan(RETENTION_DAYS, BATCH_SIZE)).thenReturn(0);

        purgeJob.purge();

        verify(outboxRepository, times(1)).deletePublishedOlderThan(RETENTION_DAYS, BATCH_SIZE);
        assertThat(output.getAll()).doesNotContain("Outbox purge");

    }

    @Test
    void purge_partialLastBatch_stopsThereAndReportsTheTotal(CapturedOutput output) {

        // 3 + 3 + 1: the third batch is smaller than the batch size, so nothing more is left to delete.
        when(outboxRepository.deletePublishedOlderThan(RETENTION_DAYS, BATCH_SIZE)).thenReturn(3, 3, 1);

        purgeJob.purge();

        verify(outboxRepository, times(3)).deletePublishedOlderThan(RETENTION_DAYS, BATCH_SIZE);
        assertThat(output.getAll()).contains("Outbox purge: deleted 7 published rows older than 7 days");

    }

    @Test
    void purge_fullBatchThenNothing_asksOnceMoreBecauseAFullBatchMeansMaybeMore(CapturedOutput output) {

        // The table held exactly one batch worth of rows: the job cannot know that, so it asks again and gets 0.
        when(outboxRepository.deletePublishedOlderThan(RETENTION_DAYS, BATCH_SIZE)).thenReturn(3, 0);

        purgeJob.purge();

        verify(outboxRepository, times(2)).deletePublishedOlderThan(RETENTION_DAYS, BATCH_SIZE);
        assertThat(output.getAll()).contains("Outbox purge: deleted 3 published rows");

    }

    @Test
    void purge_everyCall_usesTheConfiguredRetentionAndBatchSize() {

        when(outboxRepository.deletePublishedOlderThan(RETENTION_DAYS, BATCH_SIZE)).thenReturn(3, 2);

        purgeJob.purge();

        // Stubbed with the exact arguments, so any other argument would have returned the mock's default 0 and
        // changed the call count; the verify makes that explicit.
        verify(outboxRepository, times(2)).deletePublishedOlderThan(RETENTION_DAYS, BATCH_SIZE);

    }

    @Test
    void purge_aBatchFails_theExceptionPropagatesToTheScheduler() {

        when(outboxRepository.deletePublishedOlderThan(RETENTION_DAYS, BATCH_SIZE))
                .thenReturn(3)
                .thenThrow(new DataAccessResourceFailureException("connection lost"));

        // Not swallowed: Spring's scheduler logs it and runs the job again at the next cron time. The first batch
        // was a call of its own, so its deletion is not undone.
        assertThatThrownBy(() -> purgeJob.purge()).isInstanceOf(DataAccessResourceFailureException.class);

        verify(outboxRepository, times(2)).deletePublishedOlderThan(RETENTION_DAYS, BATCH_SIZE);

    }
}
