package com.cagritasoz.user_service.consumer;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class ProcessedEventsPurgeJobTest {

    private static final int RETENTION_DAYS = 30;

    private static final int BATCH_SIZE = 100;

    @Mock
    private ProcessedEvents processedEvents;

    private ProcessedEventsPurgeJob job() {
        return new ProcessedEventsPurgeJob(processedEvents, RETENTION_DAYS, BATCH_SIZE);
    }

    @ParameterizedTest
    @CsvSource({"0, 100", "-1, 100", "30, 0", "30, -5"})
    void constructor_retentionOrBatchBelowOne_refusesToStart(int retentionDays, int batchSize) {

        assertThatThrownBy(() -> new ProcessedEventsPurgeJob(processedEvents, retentionDays, batchSize))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.processed-events.purge");

    }

    // One call, and it was short: nothing older is left, so no second call.
    @Test
    void purge_partialBatch_stopsAfterOneCall(CapturedOutput output) {

        when(processedEvents.purgeOlderThan(RETENTION_DAYS, BATCH_SIZE)).thenReturn(7);

        job().purge();

        verify(processedEvents).purgeOlderThan(RETENTION_DAYS, BATCH_SIZE);
        verifyNoMoreInteractions(processedEvents);
        assertThat(output.getOut()).contains("deleted 7 rows");

    }

    // A full batch may mean more behind it: ask again, until one comes back short.
    @Test
    void purge_fullBatches_keepGoingUntilOneIsShort(CapturedOutput output) {

        when(processedEvents.purgeOlderThan(RETENTION_DAYS, BATCH_SIZE)).thenReturn(BATCH_SIZE, BATCH_SIZE, 40);

        job().purge();

        verify(processedEvents, times(3)).purgeOlderThan(RETENTION_DAYS, BATCH_SIZE);
        assertThat(output.getOut()).contains("deleted 240 rows");

    }

    @Test
    void purge_nothingToDelete_isSilent(CapturedOutput output) {

        when(processedEvents.purgeOlderThan(RETENTION_DAYS, BATCH_SIZE)).thenReturn(0);

        job().purge();

        verify(processedEvents).purgeOlderThan(RETENTION_DAYS, BATCH_SIZE);
        assertThat(output.getOut()).doesNotContain("Processed-events purge");

    }

    // A database error is the scheduler's to log; the next day's run starts over.
    @Test
    void purge_databaseError_propagates() {

        when(processedEvents.purgeOlderThan(RETENTION_DAYS, BATCH_SIZE)).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> job().purge()).isInstanceOf(IllegalStateException.class);

    }
}
