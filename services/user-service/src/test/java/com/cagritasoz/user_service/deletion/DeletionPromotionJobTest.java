package com.cagritasoz.user_service.deletion;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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

// No Spring, no database: the service is a mock, so these tests pin what the job itself decides - one batch per
// tick, one account at a time, a failure on one account does not stop the others, what is counted and what is
// logged. That the real service and the real scheduler fit together is checked by DeletionPromotionFlowIT.
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class DeletionPromotionJobTest {

    private static final int BATCH_SIZE = 25;

    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID THIRD = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Mock
    private DeletionPromotionService promotionService;

    private SimpleMeterRegistry meters;

    private DeletionPromotionJob job;

    @BeforeEach
    void createJob() {

        meters = new SimpleMeterRegistry();
        job = new DeletionPromotionJob(promotionService, meters, BATCH_SIZE);

    }

    private double promoted() {
        return meters.get("user.deletion.promoted").counter().count();
    }

    private double failures() {
        return meters.get("user.deletion.promotion.failures").counter().count();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void constructor_batchSizeBelowOne_refusesToStart(int batchSize) {

        assertThatThrownBy(() -> new DeletionPromotionJob(promotionService, meters, batchSize))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.user.deletion.promoter.batch-size");

    }

    @Test
    void promoteDue_nobodyDue_promotesNothing() {

        when(promotionService.findDue(BATCH_SIZE)).thenReturn(List.of());

        job.promoteDue();

        verify(promotionService).findDue(BATCH_SIZE);
        verifyNoMoreInteractions(promotionService);
        assertThat(promoted()).isZero();
        assertThat(failures()).isZero();

    }

    @Test
    void promoteDue_dueAccounts_arePromotedOneByOneInTheOrderOfTheList() {

        when(promotionService.findDue(BATCH_SIZE)).thenReturn(List.of(FIRST, SECOND, THIRD));
        when(promotionService.promote(FIRST)).thenReturn(true);
        when(promotionService.promote(SECOND)).thenReturn(true);
        when(promotionService.promote(THIRD)).thenReturn(true);

        job.promoteDue();

        InOrder order = inOrder(promotionService);
        order.verify(promotionService).findDue(BATCH_SIZE);
        order.verify(promotionService).promote(FIRST);
        order.verify(promotionService).promote(SECOND);
        order.verify(promotionService).promote(THIRD);
        assertThat(promoted()).isEqualTo(3);
        assertThat(failures()).isZero();

    }

    // A cancel or another promoter won the row: normal, so neither counted as a promotion nor as a failure.
    @Test
    void promoteDue_accountLostToACancel_isNotCounted() {

        when(promotionService.findDue(BATCH_SIZE)).thenReturn(List.of(FIRST, SECOND));
        when(promotionService.promote(FIRST)).thenReturn(false);
        when(promotionService.promote(SECOND)).thenReturn(true);

        job.promoteDue();

        assertThat(promoted()).isEqualTo(1);
        assertThat(failures()).isZero();

    }

    @Test
    void promoteDue_oneAccountFails_theOthersAreStillPromotedAndTheFailureIsCounted() {

        when(promotionService.findDue(BATCH_SIZE)).thenReturn(List.of(FIRST, SECOND, THIRD));
        when(promotionService.promote(FIRST)).thenReturn(true);
        when(promotionService.promote(SECOND)).thenThrow(new IllegalStateException("boom"));
        when(promotionService.promote(THIRD)).thenReturn(true);

        job.promoteDue(); // Does not throw: the scheduler would only log it and the loop would be cut short.

        verify(promotionService).promote(THIRD);
        assertThat(promoted()).isEqualTo(2);
        assertThat(failures()).isEqualTo(1);

    }

    @Test
    void promoteDue_everyAccountFails_eachIsTriedOnceAndEachCounted() {

        when(promotionService.findDue(BATCH_SIZE)).thenReturn(List.of(FIRST, SECOND));
        when(promotionService.promote(FIRST)).thenThrow(new IllegalStateException("boom"));
        when(promotionService.promote(SECOND)).thenThrow(new IllegalStateException("boom"));

        job.promoteDue();

        // One batch per tick: failing accounts are not retried in a loop, they wait for the next tick.
        verify(promotionService).findDue(BATCH_SIZE);
        verify(promotionService).promote(FIRST);
        verify(promotionService).promote(SECOND);
        assertThat(promoted()).isZero();
        assertThat(failures()).isEqualTo(2);

    }

    // The tick that cannot even read its work list (database down) has nothing to continue with: the error goes to
    // Spring's scheduler, which logs it and keeps the schedule, like the relay's database errors.
    @Test
    void promoteDue_workListCannotBeRead_theErrorPropagates() {

        when(promotionService.findDue(BATCH_SIZE)).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> job.promoteDue()).isInstanceOf(IllegalStateException.class);

        verify(promotionService, never()).promote(FIRST);

    }

    @Test
    void promoteDue_successfulPromotion_logsTheUserId(CapturedOutput output) {

        when(promotionService.findDue(BATCH_SIZE)).thenReturn(List.of(FIRST));
        when(promotionService.promote(FIRST)).thenReturn(true);

        job.promoteDue();

        assertThat(output.getOut()).contains("Deletion started for user " + FIRST);

    }

    // A database error can quote the row it rejected, and that row holds the user's email address: the log gets the
    // user id and the class of the root cause, never the exception's message or stack trace.
    @Test
    void promoteDue_failure_logsTheIdAndTheCauseClassButNeverTheMessage(CapturedOutput output) {

        String secretInTheMessage = "Failing row contains (arthur.morgan@example.com, Arthur Morgan)";
        when(promotionService.findDue(BATCH_SIZE)).thenReturn(List.of(FIRST));
        when(promotionService.promote(FIRST))
                .thenThrow(new DataIntegrityViolationException("could not execute statement", new SQLException(secretInTheMessage)));

        job.promoteDue();

        String log = output.getOut() + output.getErr();
        assertThat(log).contains(FIRST.toString()).contains(SQLException.class.getName());
        assertThat(log).doesNotContain("arthur.morgan@example.com").doesNotContain("Failing row");

    }
}
