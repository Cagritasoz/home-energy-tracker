package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.outbox.OutboxService;
import com.cagritasoz.user_service.user.AccountDeletedException;
import com.cagritasoz.user_service.user.User;
import com.cagritasoz.user_service.user.UserNotFoundException;
import com.cagritasoz.user_service.user.UserRepository;
import com.cagritasoz.user_service.user.UserResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

// The customer's side of the deletion saga: ask for the deletion (ACTIVE -> DELETION_SCHEDULED) and cancel it
// (DELETION_SCHEDULED -> ACTIVE). Everything after the deadline is done by background jobs, not by a request.
//
// Each method is one transaction: a compare-and-set UPDATE that reports the rows it changed, and - only for the
// request that changed the row - the outbox event written in the same transaction. A request that changes nothing
// re-reads the row to tell "already in the state you asked for" (a successful repeat) from "too late" or "no such
// account".
@Service
public class DeletionSchedulingService {

    private final UserRepository userRepository;

    private final OutboxService outboxService;

    private final Duration grace;

    public DeletionSchedulingService(UserRepository userRepository,
                               OutboxService outboxService,
                               @Value("${app.user.deletion.grace}") Duration grace) {

        // Zero or negative would make an account deletable at once while the API still promises a cancel window,
        // so a configuration mistake fails the start instead of silently removing the grace period.
        if (grace.isZero() || grace.isNegative()) {

            throw new IllegalArgumentException("app.user.deletion.grace must be positive but was " + grace);

        }

        this.userRepository = userRepository;
        this.outboxService = outboxService;
        this.grace = grace;
    }

    // Idempotent: a repeat while the deletion is already scheduled answers the same deadline and writes no event.
    @Transactional
    public UserResponse scheduleDeletion(UUID id) {

        // Duration.toString() is an ISO-8601 duration ("PT5M"), which the query casts to a Postgres interval.
        if (userRepository.scheduleDeletion(id, grace.toString()) == 1) {

            // The update was native, so this is a fresh read (clearAutomatically): the new version, the
            // database-computed deadline and the trigger-stamped updated_at, which the event is built from.
            User scheduled = userRepository.findById(id)
                    .orElseThrow(() -> new IllegalStateException("User " + id + " missing after scheduleDeletion."));

            outboxService.recordUserDeletionScheduled(scheduled);

            return UserResponse.from(scheduled);

        }

        User user = userRepository.findById(id).orElseThrow(UserNotFoundException::new);

        return switch (user.getStatus()) {

            case DELETION_SCHEDULED -> UserResponse.from(user);

            case DELETING, DELETED -> throw new AccountDeletedException();

            // The row was ACTIVE when the update ran and is ACTIVE now, so someone scheduled and canceled between
            // the two statements. Nothing wrong with the request, but the answer would be a lie either way: ask the
            // client to retry (409 concurrent-modification).
            case ACTIVE -> throw new ObjectOptimisticLockingFailureException(User.class, id);

        };

    }

    // Not asked for by the account's owner: the account has to go now. Used when its Keycloak user no longer exists
    // (an operator deleted it by hand), so nobody can log in to it and its address blocks a new registration. It is
    // the same state machine with a grace period of zero: an ACTIVE account is scheduled with the deadline set to now
    // and announced like any other schedule, and the promoter takes it on its next tick. An account that is already
    // scheduled further out gets its deadline moved to now (no event; see bringDeletionForward). Anything else (due
    // already, DELETING, DELETED, unknown) has nothing left to do, so this is idempotent and a no-op there.
    @Transactional
    public void scheduleForImmediateDeletion(UUID id) {

        if (userRepository.scheduleDeletion(id, Duration.ZERO.toString()) == 1) {

            User scheduled = userRepository.findById(id)
                    .orElseThrow(() -> new IllegalStateException("User " + id + " missing after scheduleDeletion."));

            outboxService.recordUserDeletionScheduled(scheduled);

            return;

        }

        userRepository.bringDeletionForward(id);

    }

    // Idempotent: cancelling an account that is not scheduled changes nothing and succeeds.
    @Transactional
    public void cancelDeletion(UUID id) {

        if (userRepository.cancelDeletion(id) == 1) {

            User active = userRepository.findById(id)
                    .orElseThrow(() -> new IllegalStateException("User " + id + " missing after cancelDeletion."));

            outboxService.recordUserDeletionCancelled(active);

            return;

        }

        User user = userRepository.findById(id).orElseThrow(UserNotFoundException::new);

        switch (user.getStatus()) {

            case ACTIVE -> { } // Nothing to cancel, or a concurrent cancel won: either way the account is ACTIVE.

            // The update requires the deadline to lie ahead, so a scheduled row that still got 0 is overdue and waits
            // for the promoter. (A schedule-cancel-schedule by another device inside these two statements would be
            // answered the same way; harmless, the request is refused either way.)
            case DELETION_SCHEDULED -> throw new DeletionDeadlinePassedException();

            case DELETING, DELETED -> throw new AccountDeletedException();

        }

    }
}
