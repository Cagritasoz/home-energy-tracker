package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.keycloak.KeycloakCircuitOpenException;
import com.cagritasoz.user_service.keycloak.KeycloakUnavailableException;
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

// No Spring, no database, no HTTP: the service is a mock, so these tests pin what the job itself decides - one batch per
// tick, one account at a time, what ends a tick early, what is counted and what is logged. That the pieces fit together
// against a real database and a faked Keycloak is checked by KeycloakDeletionFlowIT.
@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class KeycloakDeletionJobTest {

    private static final int BATCH_SIZE = 20;

    private static final UUID FIRST = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private static final UUID SECOND = UUID.fromString("00000000-0000-0000-0000-000000000002");
    private static final UUID THIRD = UUID.fromString("00000000-0000-0000-0000-000000000003");

    @Mock
    private KeycloakDeletionService deletionService;

    private SimpleMeterRegistry meters;

    private KeycloakDeletionJob job;

    @BeforeEach
    void createJob() {

        meters = new SimpleMeterRegistry();
        job = new KeycloakDeletionJob(deletionService, meters, BATCH_SIZE);

    }

    private double deleted() {
        return meters.get("user.deletion.keycloak.deleted").counter().count();
    }

    private double failures() {
        return meters.get("user.deletion.keycloak.failures").counter().count();
    }

    @ParameterizedTest
    @ValueSource(ints = {0, -1})
    void constructor_batchSizeBelowOne_refusesToStart(int batchSize) {

        assertThatThrownBy(() -> new KeycloakDeletionJob(deletionService, meters, batchSize))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.user.deletion.keycloak.batch-size");

    }

    @Test
    void deleteDue_nothingPending_doesNothing() {

        when(deletionService.findPending(BATCH_SIZE)).thenReturn(List.of());

        job.deleteDue();

        verify(deletionService).findPending(BATCH_SIZE);
        verifyNoMoreInteractions(deletionService);
        assertThat(deleted()).isZero();
        assertThat(failures()).isZero();

    }

    @Test
    void deleteDue_pendingAccounts_areDeletedOneByOneInTheOrderOfTheList() {

        when(deletionService.findPending(BATCH_SIZE)).thenReturn(List.of(FIRST, SECOND, THIRD));
        when(deletionService.deleteFromKeycloak(FIRST)).thenReturn(true);
        when(deletionService.deleteFromKeycloak(SECOND)).thenReturn(true);
        when(deletionService.deleteFromKeycloak(THIRD)).thenReturn(true);

        job.deleteDue();

        InOrder order = inOrder(deletionService);
        order.verify(deletionService).findPending(BATCH_SIZE);
        order.verify(deletionService).deleteFromKeycloak(FIRST);
        order.verify(deletionService).deleteFromKeycloak(SECOND);
        order.verify(deletionService).deleteFromKeycloak(THIRD);
        assertThat(deleted()).isEqualTo(3);
        assertThat(failures()).isZero();

    }

    // Another instance stamped the account first: normal, neither a deletion of this instance nor a failure.
    @Test
    void deleteDue_accountAlreadyStampedByAnotherInstance_isNotCounted() {

        when(deletionService.findPending(BATCH_SIZE)).thenReturn(List.of(FIRST, SECOND));
        when(deletionService.deleteFromKeycloak(FIRST)).thenReturn(false);
        when(deletionService.deleteFromKeycloak(SECOND)).thenReturn(true);

        job.deleteDue();

        assertThat(deleted()).isEqualTo(1);
        assertThat(failures()).isZero();

    }

    @Test
    void deleteDue_keycloakFailsForOneAccount_theOthersAreStillDeletedAndTheFailureIsCounted() {

        when(deletionService.findPending(BATCH_SIZE)).thenReturn(List.of(FIRST, SECOND, THIRD));
        when(deletionService.deleteFromKeycloak(FIRST)).thenReturn(true);
        when(deletionService.deleteFromKeycloak(SECOND)).thenThrow(new KeycloakUnavailableException("Keycloak answered 500 to a user delete", true));
        when(deletionService.deleteFromKeycloak(THIRD)).thenReturn(true);

        job.deleteDue(); // Does not throw: the scheduler would only log it and the loop would be cut short.

        verify(deletionService).deleteFromKeycloak(THIRD);
        assertThat(deleted()).isEqualTo(2);
        assertThat(failures()).isEqualTo(1);

    }

    // The breaker refuses every call while it is open: trying the rest of the list would only produce the same refusal
    // for each account, so the tick ends there. Not a failure of the account (it was never tried).
    @Test
    void deleteDue_circuitOpen_endsTheTickAtTheFirstRefusalWithoutCountingAFailure(CapturedOutput output) {

        when(deletionService.findPending(BATCH_SIZE)).thenReturn(List.of(FIRST, SECOND, THIRD));
        when(deletionService.deleteFromKeycloak(FIRST)).thenReturn(true);
        when(deletionService.deleteFromKeycloak(SECOND)).thenThrow(new KeycloakCircuitOpenException(null));

        job.deleteDue();

        verify(deletionService, never()).deleteFromKeycloak(THIRD);
        assertThat(deleted()).isEqualTo(1);
        assertThat(failures()).isZero();
        assertThat(output.getOut() + output.getErr()).contains("circuit breaker is open");

    }

    // A failure that is not Keycloak's, most likely the database refusing the stamp: counted, the loop goes on.
    @Test
    void deleteDue_stampFailsForOneAccount_theOthersAreStillHandled() {

        when(deletionService.findPending(BATCH_SIZE)).thenReturn(List.of(FIRST, SECOND));
        when(deletionService.deleteFromKeycloak(FIRST)).thenThrow(new IllegalStateException("boom"));
        when(deletionService.deleteFromKeycloak(SECOND)).thenReturn(true);

        job.deleteDue();

        assertThat(deleted()).isEqualTo(1);
        assertThat(failures()).isEqualTo(1);

    }

    @Test
    void deleteDue_everyAccountFails_eachIsTriedOnceAndEachCounted() {

        when(deletionService.findPending(BATCH_SIZE)).thenReturn(List.of(FIRST, SECOND));
        when(deletionService.deleteFromKeycloak(FIRST)).thenThrow(new KeycloakUnavailableException("down", true));
        when(deletionService.deleteFromKeycloak(SECOND)).thenThrow(new KeycloakUnavailableException("down", true));

        job.deleteDue();

        // One batch per tick: failing accounts wait for the next tick, they are not retried in a loop here.
        verify(deletionService).findPending(BATCH_SIZE);
        verify(deletionService).deleteFromKeycloak(FIRST);
        verify(deletionService).deleteFromKeycloak(SECOND);
        assertThat(failures()).isEqualTo(2);

    }

    @Test
    void deleteDue_workListCannotBeRead_theErrorPropagates() {

        when(deletionService.findPending(BATCH_SIZE)).thenThrow(new IllegalStateException("database down"));

        assertThatThrownBy(() -> job.deleteDue()).isInstanceOf(IllegalStateException.class);

        verify(deletionService, never()).deleteFromKeycloak(FIRST);

    }

    @Test
    void deleteDue_success_logsTheAccountId(CapturedOutput output) {

        when(deletionService.findPending(BATCH_SIZE)).thenReturn(List.of(FIRST));
        when(deletionService.deleteFromKeycloak(FIRST)).thenReturn(true);

        job.deleteDue();

        assertThat(output.getOut()).contains("Keycloak user deleted for account " + FIRST);

    }

    // The client writes its own messages (an HTTP status, a description) and never copies anything Keycloak sent, so the
    // message is logged with the id.
    @Test
    void deleteDue_keycloakFailure_logsTheIdAndTheClientsOwnMessage(CapturedOutput output) {

        when(deletionService.findPending(BATCH_SIZE)).thenReturn(List.of(FIRST));
        when(deletionService.deleteFromKeycloak(FIRST))
                .thenThrow(new KeycloakUnavailableException("Keycloak answered 503 to a user delete", true));

        job.deleteDue();

        assertThat(output.getOut() + output.getErr()).contains(FIRST.toString()).contains("Keycloak answered 503 to a user delete");

    }

    // Anything else, such as a database error, can quote the row it rejected, and that row holds an email address: the id
    // and the class of the root cause only.
    @Test
    void deleteDue_otherFailure_logsTheIdAndTheCauseClassButNeverTheMessage(CapturedOutput output) {

        String secretInTheMessage = "Failing row contains (arthur.morgan@example.com, Arthur Morgan)";
        when(deletionService.findPending(BATCH_SIZE)).thenReturn(List.of(FIRST));
        when(deletionService.deleteFromKeycloak(FIRST))
                .thenThrow(new DataIntegrityViolationException("could not execute statement", new SQLException(secretInTheMessage)));

        job.deleteDue();

        String log = output.getOut() + output.getErr();
        assertThat(log).contains(FIRST.toString()).contains(SQLException.class.getName());
        assertThat(log).doesNotContain("arthur.morgan@example.com").doesNotContain("Failing row");

    }
}
