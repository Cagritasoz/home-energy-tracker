package com.cagritasoz.user_service.deletion.ack;

import com.cagritasoz.user_service.consumer.ProcessedEvents;
import com.cagritasoz.user_service.consumer.ReceivedEvent;
import com.cagritasoz.user_service.deletion.UserDeletionParticipantRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// Applies one participant's confirmation: records that this participant has finished cleaning up for this user.
//
// One transaction for the whole record: the claim in processed_events and the update of the participant row commit
// together or not at all. If the update fails, the claim is rolled back with it and the redelivered event is applied
// again for real; if the event arrives twice, the second claim finds the first and nothing else happens.
//
// The users row is not touched: an ack never takes its lock, never bumps its version and never conflicts with a
// profile update.
@Service
public class ParticipantAckService {

    public enum Outcome {

        // The participant's row was pending and is now confirmed.
        ACKED,

        // The event was applied before (a redelivery or a re-published copy): nothing was done.
        ALREADY_PROCESSED,

        // New event, but there was nothing pending to confirm: the participant is not required for this deletion, the
        // row is already confirmed or waived, or the user is not being deleted. Ignored on purpose; the claim is kept,
        // so the same event is not examined again.
        NOT_PENDING

    }

    private final ProcessedEvents processedEvents;

    private final UserDeletionParticipantRepository participantRepository;

    public ParticipantAckService(ProcessedEvents processedEvents, UserDeletionParticipantRepository participantRepository) {
        this.processedEvents = processedEvents;
        this.participantRepository = participantRepository;
    }

    @Transactional
    public Outcome acknowledge(ReceivedEvent event, UUID userId, ParticipantAck ack) {

        if (!processedEvents.claim(event.eventId(), ack.handler(), event.eventType(), event.aggregateId())) {

            return Outcome.ALREADY_PROCESSED;

        }

        return participantRepository.markAcked(userId, ack.participant()) == 1
                ? Outcome.ACKED
                : Outcome.NOT_PENDING;

    }
}
