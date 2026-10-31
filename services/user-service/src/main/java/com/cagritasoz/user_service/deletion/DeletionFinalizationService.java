package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.outbox.OutboxService;
import com.cagritasoz.user_service.user.User;
import com.cagritasoz.user_service.user.UserRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

// The finalizer's work: close a deletion. A DELETING account becomes DELETED once two things are true:
//   - its Keycloak user has been deleted for the whole quiet period. No new access token can be issued after the
//     delete, and every token issued before it expires within five minutes, so after the quiet period nothing can still
//     be acting for the user;
//   - every participant that was required for this deletion has confirmed (or was waived by a migration).
// UserDeletionFinalized then tells every service to sweep once more, which catches whatever a request that was already
// in flight when its service handled UserDeletionStarted may have created since.
//
// One account per transaction, like the promoter, and for the same reason: an account that cannot be finalized must not
// hold up the others. The compare-and-set UPDATE is both the check and the claim. The job that drives it is a separate
// bean (DeletionFinalizationJob), because this class's methods are @Transactional.
//
// A required participant that never confirms stalls only its own users' deletions, and the watchdog gauge names it.
// Nothing is ever forced through: a stuck DELETING row harms nobody (the user is already gone from Keycloak and scrubbed),
// whereas finalizing early would hide an incomplete deletion.
@Service
@EnableConfigurationProperties(DeletionProperties.class)
public class DeletionFinalizationService {

    private final UserRepository userRepository;

    private final OutboxService outboxService;

    // As an ISO-8601 duration, which is how Postgres reads it as an interval ("PT10M").
    private final String quietPeriod;

    public DeletionFinalizationService(UserRepository userRepository,
                                       OutboxService outboxService,
                                       DeletionProperties properties) {

        this.userRepository = userRepository;
        this.outboxService = outboxService;
        this.quietPeriod = properties.quietPeriod().toString();

    }

    // Accounts that are ready, longest-gone first, at most `limit`. A plain read: nothing is locked until finalize() claims each.
    public List<UUID> findReady(int limit) {

        return userRepository.findIdsReadyForFinalization(quietPeriod, limit);

    }

    // true: this call finalized the account. false: it did not - another finalizer got there first, or a condition no
    // longer holds - and nothing was written. Both are normal.
    @Transactional
    public boolean finalizeDeletion(UUID id) {

        // The compare-and-set repeats every condition, so a stale work list cannot finalize an account too early.
        if (userRepository.finalizeDeletion(id, quietPeriod) == 0) {

            return false;

        }

        // The update was native: a fresh read gives the new version and the database's deleted_at, which the event is built from.
        User deleted = userRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("User " + id + " missing after finalizeDeletion."));

        outboxService.recordUserDeletionFinalized(deleted);

        return true;

    }
}
