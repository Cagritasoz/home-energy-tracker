package com.cagritasoz.user_service.deletion.ack;

import com.cagritasoz.user_service.deletion.UserDeletionParticipantRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

// The mock is a field because Micrometer holds a gauge's object weakly.
@ExtendWith(MockitoExtension.class)
class ParticipantAckMetricsTest {

    @Mock
    private UserDeletionParticipantRepository participantRepository;

    private SimpleMeterRegistry meters;

    @BeforeEach
    void bind() {

        meters = new SimpleMeterRegistry();
        new ParticipantAckMetrics(new ParticipantAcks(List.of(
                new ParticipantAck("devices", "device.events.v1", "UserDevicesUnclaimed", 1),
                new ParticipantAck("usage", "usage.events.v1", "UserReadingsDeleted", 1))), participantRepository).bindTo(meters);

    }

    private double pending(String participant) {

        return meters.get("user.deletion.participant.pending.acks").tag("participant", participant).gauge().value();

    }

    // One gauge per participant that can confirm, each asking about its own participant, live on every read.
    @Test
    void pendingAcks_eachParticipantHasItsOwnGaugeReadLive() {

        when(participantRepository.countPending("devices")).thenReturn(2L, 0L);
        when(participantRepository.countPending("usage")).thenReturn(5L);

        assertThat(pending("devices")).isEqualTo(2);
        assertThat(pending("usage")).isEqualTo(5);
        assertThat(pending("devices")).isZero();

    }

    @Test
    void bindTo_noParticipants_registersNothing() {

        SimpleMeterRegistry empty = new SimpleMeterRegistry();

        new ParticipantAckMetrics(new ParticipantAcks(List.of()), participantRepository).bindTo(empty);

        assertThat(empty.find("user.deletion.participant.pending.acks").gauges()).isEmpty();

    }
}
