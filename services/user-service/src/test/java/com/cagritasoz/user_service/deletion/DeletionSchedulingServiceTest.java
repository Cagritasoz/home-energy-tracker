package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.outbox.OutboxService;
import com.cagritasoz.user_service.testsupport.data.UserFixtures;
import com.cagritasoz.user_service.user.AccountDeletedException;
import com.cagritasoz.user_service.user.User;
import com.cagritasoz.user_service.user.UserNotFoundException;
import com.cagritasoz.user_service.user.UserRepository;
import com.cagritasoz.user_service.user.UserResponse;
import com.cagritasoz.user_service.user.UserStatus;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// No Spring, no database: a mocked repository answers what the compare-and-set queries would, so these tests
// pin the decisions the service makes from the row counts and the re-read - which event is written, which answer
// the caller gets. What the queries themselves do is proven against Postgres in UserRepositoryIT.
@ExtendWith(MockitoExtension.class)
class DeletionSchedulingServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final Duration GRACE = Duration.ofMinutes(5);

    private static final Instant DEADLINE = Instant.parse("2026-02-14T23:50:00Z");

    @Mock
    private UserRepository userRepository;

    @Mock
    private OutboxService outboxService;

    private DeletionSchedulingService service() {
        return new DeletionSchedulingService(userRepository, outboxService, GRACE);
    }

    private User userWithStatus(UserStatus status) {
        return UserFixtures.activeUser().id(USER_ID).status(status).build();
    }

    private User scheduledUser() {
        return UserFixtures.activeUser().id(USER_ID)
                .status(UserStatus.DELETION_SCHEDULED)
                .version(1L)
                .deletionRequestedAt(Instant.parse("2026-01-31T23:50:00Z"))
                .deletionScheduledFor(DEADLINE)
                .build();
    }

    // --- the grace period setting

    @ParameterizedTest
    @ValueSource(strings = {"PT0S", "-PT1S", "-PT5M"})
    void constructor_zeroOrNegativeGrace_refusesToStart(String grace) {

        assertThatThrownBy(() -> new DeletionSchedulingService(userRepository, outboxService, Duration.parse(grace)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.user.deletion.grace");

    }

    @Test
    void constructor_positiveGrace_isAccepted() {

        assertThatCode(() -> new DeletionSchedulingService(userRepository, outboxService, Duration.ofNanos(1)))
                .doesNotThrowAnyException();

    }

    // --- scheduleForImmediateDeletion (an orphaned account that has to go now)

    // Same state machine as the customer's request, with a grace of zero (not the configured one): the account is
    // scheduled with the deadline set to now, and announced like any other schedule.
    @Test
    void scheduleForImmediateDeletion_activeAccount_schedulesWithZeroGraceAndRecordsTheEvent() {

        User scheduled = scheduledUser();
        when(userRepository.scheduleDeletion(USER_ID, "PT0S")).thenReturn(1);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(scheduled));

        service().scheduleForImmediateDeletion(USER_ID);

        verify(outboxService).recordUserDeletionScheduled(scheduled);
        verify(userRepository, never()).bringDeletionForward(USER_ID);

    }

    // Already scheduled further out: the deadline moves to now. Nothing is announced again.
    @Test
    void scheduleForImmediateDeletion_alreadyScheduled_movesTheDeadlineAndWritesNoEvent() {

        when(userRepository.scheduleDeletion(USER_ID, "PT0S")).thenReturn(0);
        when(userRepository.bringDeletionForward(USER_ID)).thenReturn(1);

        service().scheduleForImmediateDeletion(USER_ID);

        verify(userRepository).bringDeletionForward(USER_ID);
        verifyNoInteractions(outboxService);

    }

    // Already due, already DELETING or DELETED, or gone: both updates match nothing, and that is fine.
    @Test
    void scheduleForImmediateDeletion_nothingLeftToDo_isANoOp() {

        when(userRepository.scheduleDeletion(USER_ID, "PT0S")).thenReturn(0);
        when(userRepository.bringDeletionForward(USER_ID)).thenReturn(0);

        assertThatCode(() -> service().scheduleForImmediateDeletion(USER_ID)).doesNotThrowAnyException();

        verifyNoInteractions(outboxService);

    }

    // --- scheduleDeletion

    @Test
    void scheduleDeletion_activeAccount_schedulesWithTheConfiguredGraceAndRecordsTheEvent() {

        // 1 = this call won the compare-and-set; the row is then re-read (the update was native) for the event.
        when(userRepository.scheduleDeletion(USER_ID, "PT5M")).thenReturn(1);
        User scheduled = scheduledUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(scheduled));

        UserResponse response = service().scheduleDeletion(USER_ID);

        verify(outboxService).recordUserDeletionScheduled(scheduled);
        assertThat(response.status()).isEqualTo(UserStatus.DELETION_SCHEDULED);
        assertThat(response.deletionScheduledFor()).isEqualTo(DEADLINE);

    }

    // The grace goes to the database as an ISO-8601 duration, which Postgres casts to an interval.
    @Test
    void scheduleDeletion_passesTheGraceAsAnIsoDuration() {

        DeletionSchedulingService thirtyDays = new DeletionSchedulingService(userRepository, outboxService, Duration.ofDays(30));
        when(userRepository.scheduleDeletion(USER_ID, "PT720H")).thenReturn(1);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(scheduledUser()));

        thirtyDays.scheduleDeletion(USER_ID);

        verify(userRepository).scheduleDeletion(USER_ID, "PT720H");

    }

    // A repeat (or the losing request of two at once) is a success with the same deadline, and announces nothing.
    @Test
    void scheduleDeletion_alreadyScheduled_answersTheSameDeadlineAndWritesNoEvent() {

        when(userRepository.scheduleDeletion(USER_ID, "PT5M")).thenReturn(0);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(scheduledUser()));

        UserResponse response = service().scheduleDeletion(USER_ID);

        assertThat(response.status()).isEqualTo(UserStatus.DELETION_SCHEDULED);
        assertThat(response.deletionScheduledFor()).isEqualTo(DEADLINE);
        verifyNoInteractions(outboxService);

    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"DELETING", "DELETED"})
    void scheduleDeletion_deletingOrDeletedAccount_throwsAccountDeleted(UserStatus status) {

        when(userRepository.scheduleDeletion(USER_ID, "PT5M")).thenReturn(0);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userWithStatus(status)));

        assertThatThrownBy(() -> service().scheduleDeletion(USER_ID)).isInstanceOf(AccountDeletedException.class);

        verifyNoInteractions(outboxService);

    }

    @Test
    void scheduleDeletion_unknownId_throwsUserNotFound() {

        when(userRepository.scheduleDeletion(USER_ID, "PT5M")).thenReturn(0);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().scheduleDeletion(USER_ID)).isInstanceOf(UserNotFoundException.class);

        verifyNoInteractions(outboxService);

    }

    // The row was ACTIVE for the update and ACTIVE again for the re-read: another request scheduled and cancelled in
    // between. Neither "scheduled" nor "not scheduled" would be a true answer, so the client is asked to retry.
    @Test
    void scheduleDeletion_activeAgainAfterLosingTheRace_asksTheClientToRetry() {

        when(userRepository.scheduleDeletion(USER_ID, "PT5M")).thenReturn(0);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userWithStatus(UserStatus.ACTIVE)));

        assertThatThrownBy(() -> service().scheduleDeletion(USER_ID))
                .isInstanceOf(ObjectOptimisticLockingFailureException.class);

        verifyNoInteractions(outboxService);

    }

    // Impossible inside one transaction (the update just committed this row), so it is an error, not a swallowed case.
    @Test
    void scheduleDeletion_userMissingAfterTheUpdateWon_throwsIllegalState() {

        when(userRepository.scheduleDeletion(USER_ID, "PT5M")).thenReturn(1);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().scheduleDeletion(USER_ID)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(outboxService);

    }

    // --- cancelDeletion

    @Test
    void cancelDeletion_scheduledBeforeTheDeadline_cancelsAndRecordsTheEvent() {

        when(userRepository.cancelDeletion(USER_ID)).thenReturn(1);
        User active = userWithStatus(UserStatus.ACTIVE);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(active));

        service().cancelDeletion(USER_ID);

        verify(outboxService).recordUserDeletionCancelled(active);

    }

    // Nothing to cancel, or a concurrent cancel won: either way the account is ACTIVE and the request has succeeded.
    @Test
    void cancelDeletion_alreadyActive_isASilentNoOp() {

        when(userRepository.cancelDeletion(USER_ID)).thenReturn(0);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userWithStatus(UserStatus.ACTIVE)));

        assertThatCode(() -> service().cancelDeletion(USER_ID)).doesNotThrowAnyException();

        verifyNoInteractions(outboxService);

    }

    // The query only cancels while the deadline lies ahead, so a scheduled row that still got 0 rows is overdue.
    @Test
    void cancelDeletion_scheduledButPastTheDeadline_throwsDeadlinePassed() {

        when(userRepository.cancelDeletion(USER_ID)).thenReturn(0);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(scheduledUser()));

        assertThatThrownBy(() -> service().cancelDeletion(USER_ID)).isInstanceOf(DeletionDeadlinePassedException.class);

        verifyNoInteractions(outboxService);

    }

    @ParameterizedTest
    @EnumSource(value = UserStatus.class, names = {"DELETING", "DELETED"})
    void cancelDeletion_deletingOrDeletedAccount_throwsAccountDeleted(UserStatus status) {

        when(userRepository.cancelDeletion(USER_ID)).thenReturn(0);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(userWithStatus(status)));

        assertThatThrownBy(() -> service().cancelDeletion(USER_ID)).isInstanceOf(AccountDeletedException.class);

        verifyNoInteractions(outboxService);

    }

    @Test
    void cancelDeletion_unknownId_throwsUserNotFound() {

        when(userRepository.cancelDeletion(USER_ID)).thenReturn(0);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().cancelDeletion(USER_ID)).isInstanceOf(UserNotFoundException.class);

        verifyNoInteractions(outboxService);

    }

    @Test
    void cancelDeletion_userMissingAfterTheUpdateWon_throwsIllegalState() {

        when(userRepository.cancelDeletion(USER_ID)).thenReturn(1);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service().cancelDeletion(USER_ID)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(outboxService);

    }
}
