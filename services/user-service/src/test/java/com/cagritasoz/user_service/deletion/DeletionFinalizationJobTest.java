package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.testsupport.config.ConfigFixtures;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

// No Spring, no database: the service is a mock, so these tests pin what the job itself decides - one batch per tick,
// one account at a time, a failure on one account does not stop the others, what is counted and what is logged. That
// the real service, the real scheduler and the rest of the saga fit together is checked by DeletionSagaFlowIT.
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class DeletionFinalizationJobTest {

    private static final int BATCH_SIZE = 25;

    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID THIRD = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Mock
    private DeletionFinalizationService finalizationService;

    private SimpleMeterRegistry meters;

    private DeletionFinalizationJob job;

    @BeforeEach
    void createJob() {

        meters = new SimpleMeterRegistry();
        job = new DeletionFinalizationJob(finalizationService, meters, ConfigFixtures.deletion("finalizer.batch-size=" + BATCH_SIZE));

    }

    private double finalized() {
        return meters.get("user.deletion.finalized").counter().count();
    }

    private double failures() {
        return meters.get("user.deletion.finalization.failures").counter().count();
    }

    @Test
    void finalizeReady_nothingReady_finalizesNothing() {

        when(finalizationService.findReady(BATCH_SIZE)).thenReturn(List.of());

        job.finalizeReady();

        verify(finalizationService).findReady(BATCH_SIZE);
        verifyNoMoreInteractions(finalizationService);
        assertThat(finalized()).isZero();
        assertThat(failures()).isZero();

    }

    @Test
    void finalizeReady_readyAccounts_areFinalizedOneByOneInTheOrderOfTheList() {

        when(finalizationService.findReady(BATCH_SIZE)).thenReturn(List.of(FIRST, SECOND, THIRD));
        when(finalizationService.finalizeDeletion(FIRST)).thenReturn(true);
        when(finalizationService.finalizeDeletion(SECOND)).thenReturn(true);
        when(finalizationService.finalizeDeletion(THIRD)).thenReturn(true);

        job.finalizeReady();

        InOrder order = inOrder(finalizationService);
        order.verify(finalizationService).findReady(BATCH_SIZE);
        order.verify(finalizationService).finalizeDeletion(FIRST);
        order.verify(finalizationService).finalizeDeletion(SECOND);
        order.verify(finalizationService).finalizeDeletion(THIRD);
        assertThat(finalized()).isEqualTo(3);
        assertThat(failures()).isZero();

    }

    // Another finalizer finished it first: normal, so neither counted as a finalization by this instance nor as a failure.
    @Test
    void finalizeReady_accountLostToAnotherFinalizer_isNotCounted() {

        when(finalizationService.findReady(BATCH_SIZE)).thenReturn(List.of(FIRST, SECOND));
        when(finalizationService.finalizeDeletion(FIRST)).thenReturn(false);
        when(finalizationService.finalizeDeletion(SECOND)).thenReturn(true);

        job.finalizeReady();

        assertThat(finalized()).isEqualTo(1);
        assertThat(failures()).isZero();

    }

    @Test
    void finalizeReady_oneAccountFails_theOthersAreStillFinalizedAndTheFailureIsCounted() {

        when(finalizationService.findReady(BATCH_SIZE)).thenReturn(List.of(FIRST, SECOND, THIRD));
        when(finalizationService.finalizeDeletion(FIRST)).thenReturn(true);
        when(finalizationService.finalizeDeletion(SECOND)).thenThrow(new IllegalStateException("boom"));
        when(finalizationService.finalizeDeletion(THIRD)).thenReturn(true);

        job.finalizeReady(); // Does not throw: the scheduler would only log it and the loop would be cut short.

        verify(finalizationService).finalizeDeletion(THIRD);
        assertThat(finalized()).isEqualTo(2);
        assertThat(failures()).isEqualTo(1);

    }

    @Test
    void finalizeReady_everyAccountFails_eachIsTriedOnceAndEachCounted() {

        when(finalizationService.findReady(BATCH_SIZE)).thenReturn(List.of(FIRST, SECOND));
        when(finalizationService.finalizeDeletion(FIRST)).thenThrow(new IllegalStateException("boom"));
        when(finalizationService.finalizeDeletion(SECOND)).thenThrow(new IllegalStateException("boom"));

        job.finalizeReady();

        // One batch per tick: failing accounts wait for the next tick instead of being retried in a loop.
        verify(finalizationService).findReady(BATCH_SIZE);
        verify(finalizationService).finalizeDeletion(FIRST);
        verify(finalizationService).finalizeDeletion(SECOND);
        assertThat(finalized()).isZero();
        assertThat(failures()).isEqualTo(2);

    }

    @Test
    void finalizeReady_workListCannotBeRead_theErrorPropagates() {

        when(finalizationService.findReady(BATCH_SIZE)).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> job.finalizeReady()).isInstanceOf(IllegalStateException.class);

        verify(finalizationService, never()).finalizeDeletion(FIRST);

    }

    @Test
    void finalizeReady_success_logsTheUserId(CapturedOutput output) {

        when(finalizationService.findReady(BATCH_SIZE)).thenReturn(List.of(FIRST));
        when(finalizationService.finalizeDeletion(FIRST)).thenReturn(true);

        job.finalizeReady();

        assertThat(output.getOut()).contains("Deletion finalized for user " + FIRST);

    }

    // A database error can quote the row it rejected: the log gets the id and the class of the root cause, never the message.
    @Test
    void finalizeReady_failure_logsTheIdAndTheCauseClassButNeverTheMessage(CapturedOutput output) {

        String secretInTheMessage = "Failing row contains (deleted+x@invalid, Deleted user)";
        when(finalizationService.findReady(BATCH_SIZE)).thenReturn(List.of(FIRST));
        when(finalizationService.finalizeDeletion(FIRST))
                .thenThrow(new DataIntegrityViolationException("could not execute statement", new SQLException(secretInTheMessage)));

        job.finalizeReady();

        String log = output.getOut() + output.getErr();
        assertThat(log).contains(FIRST.toString()).contains(SQLException.class.getName());
        assertThat(log).doesNotContain("Failing row").doesNotContain("deleted+x@invalid");

    }
}
