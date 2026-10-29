package com.cagritasoz.user_service.deletion.ack;

import com.cagritasoz.user_service.deletion.UserDeletionParticipantRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

// The watchdog's view of the confirmations: for each participant that can confirm, how many deletions are still
// waiting for it. A number that stays above zero while its participant should have answered is the alarm that names
// the stuck service (a deletion never finishes without it, by design).
@Component
public class ParticipantAckMetrics implements MeterBinder {

    private final ParticipantAcks acks;

    private final UserDeletionParticipantRepository participantRepository;

    public ParticipantAckMetrics(ParticipantAcks acks, UserDeletionParticipantRepository participantRepository) {
        this.acks = acks;
        this.participantRepository = participantRepository;
    }

    @Override
    public void bindTo(@NonNull MeterRegistry registry) {

        // One gauge per known participant, so the set of tag values is fixed and small. A gauge reads the repository
        // on every scrape, like the outbox gauges.
        for (String participant : acks.participants()) {

            Gauge.builder("user.deletion.participant.pending.acks", participantRepository,
                            repository -> repository.countPending(participant))
                    .description("Deletions still waiting for this participant's confirmation")
                    .tag("participant", participant)
                    .register(registry);

        }

    }
}
