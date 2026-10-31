package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.outbox.OutboxService;
import com.cagritasoz.user_service.user.User;
import com.cagritasoz.user_service.user.UserRepository;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

// The promoter's work: turn an account whose grace period is over from DELETION_SCHEDULED into DELETING. It is the
// point of no return - from here on nothing cancels it, the personal data is scrubbed, and every service that holds
// data about the user is told to delete it (UserDeletionStarted).
//
// One account per transaction, and the transaction is the whole promotion: the status change with the scrub, the
// participant rows and the outbox event commit together or not at all. It calls nothing outside the database, so
// there is no step that can half-fail. The job that drives it is a separate bean (DeletionPromotionJob): this
// class's methods are @Transactional, and a call from inside the same object would skip the proxy.
@Service
@EnableConfigurationProperties(DeletionProperties.class)
public class DeletionPromotionService {

    private final UserRepository userRepository;

    private final UserDeletionParticipantRepository participantRepository;

    private final OutboxService outboxService;

    // The services that must confirm the cleanup of every deletion that starts from now on. Read once at start-up
    // (DeletionProperties checks each name and that none is listed twice): the set a deletion gets is the one in force
    // at its promotion, copied into rows, so changing the configuration later never rewrites a deletion already
    // running. Empty until a participant service exists.
    private final List<String> requiredParticipants;

    public DeletionPromotionService(UserRepository userRepository,
                                    UserDeletionParticipantRepository participantRepository,
                                    OutboxService outboxService,
                                    DeletionProperties properties) {

        this.userRepository = userRepository;
        this.participantRepository = participantRepository;
        this.outboxService = outboxService;
        this.requiredParticipants = List.copyOf(properties.requiredParticipants());

    }

    // Accounts whose deadline has passed, oldest first, at most `limit`. A plain read: nothing is locked until
    // promote() claims each account.
    public List<UUID> findDue(int limit) {

        return userRepository.findIdsDueForPromotion(limit);

    }

    // true: this call promoted the account. false: it did not - a cancel (or another promoter) got there first, or
    // the account is no longer overdue - and nothing was written. Both outcomes are normal.
    @Transactional
    public boolean promote(UUID id) {

        // The compare-and-set both claims the row and decides the race with a cancel and with a second promoter
        // (see the query). Zero rows: someone else changed the row first, so there is nothing for us to do.
        if (userRepository.promoteDeletion(id) == 0) {

            return false;

        }

        // The update was native, so this is a fresh read: the scrubbed row, the new version and the database's
        // deletion_started_at, which the event is built from.
        User started = userRepository.findById(id)
                .orElseThrow(() -> new IllegalStateException("User " + id + " missing after promoteDeletion."));

        for (String participant : requiredParticipants) { // Required participant are inserted then acked later in ParticipantAckListener.

            participantRepository.insertRequired(id, participant);

        }

        outboxService.recordUserDeletionStarted(started);

        return true;

    }
}
