package com.cagritasoz.user_service.deletion.ack;

import com.cagritasoz.user_service.consumer.ProcessedEvents;
import com.cagritasoz.user_service.consumer.ReceivedEvent;
import com.cagritasoz.user_service.deletion.UserDeletionParticipantRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

// No database: the repositories answer what the claim and the update would, so these tests pin the order and the
// decisions - claim first, the participant row only for a first-time event - and the outcome each answer maps to. The
// transaction itself (claim and update committed or rolled back together) is proven against Postgres in ParticipantAckServiceIT.
@ExtendWith(MockitoExtension.class)
class ParticipantAckServiceTest {

    private static final UUID EVENT_ID = UUID.fromString("5b8d2f61-9c07-4e3a-b1d4-7a6c0e9f2d35");

    private static final UUID USER_ID = UUID.fromString("6f9619ff-8b86-4d11-b42d-00c04fc964ff");

    private static final ParticipantAck DEVICES = new ParticipantAck("devices", "device.events.v1", "UserDevicesUnclaimed", 1);

    private static final ReceivedEvent EVENT = new ReceivedEvent(EVENT_ID, "UserDevicesUnclaimed", 1, USER_ID.toString(),
            JsonNodeFactory.instance.objectNode());

    @Mock
    private ProcessedEvents processedEvents;

    @Mock
    private UserDeletionParticipantRepository participantRepository;

    private ParticipantAckService service() {
        return new ParticipantAckService(processedEvents, participantRepository);
    }

    @Test
    void acknowledge_firstTimeAndPending_claimsThenConfirms() {

        when(processedEvents.claim(EVENT_ID, "participant-ack:devices", "UserDevicesUnclaimed", USER_ID.toString())).thenReturn(true);
        when(participantRepository.markAcked(USER_ID, "devices")).thenReturn(1);

        assertThat(service().acknowledge(EVENT, USER_ID, DEVICES)).isEqualTo(ParticipantAckService.Outcome.ACKED);

        InOrder order = inOrder(processedEvents, participantRepository);
        order.verify(processedEvents).claim(EVENT_ID, "participant-ack:devices", "UserDevicesUnclaimed", USER_ID.toString());
        order.verify(participantRepository).markAcked(USER_ID, "devices");

    }

    // A redelivery or a re-published copy: the effect was applied before, so nothing is applied again.
    @Test
    void acknowledge_alreadyClaimed_doesNothingElse() {

        when(processedEvents.claim(EVENT_ID, "participant-ack:devices", "UserDevicesUnclaimed", USER_ID.toString())).thenReturn(false);

        assertThat(service().acknowledge(EVENT, USER_ID, DEVICES)).isEqualTo(ParticipantAckService.Outcome.ALREADY_PROCESSED);

        verifyNoInteractions(participantRepository);

    }

    // Not required for this deletion, already confirmed, waived, or no such deletion: ignored, but the claim stays so
    // the same event is not examined again.
    @Test
    void acknowledge_nothingPending_isIgnored() {

        when(processedEvents.claim(EVENT_ID, "participant-ack:devices", "UserDevicesUnclaimed", USER_ID.toString())).thenReturn(true);
        when(participantRepository.markAcked(USER_ID, "devices")).thenReturn(0);

        assertThat(service().acknowledge(EVENT, USER_ID, DEVICES)).isEqualTo(ParticipantAckService.Outcome.NOT_PENDING);

        verify(participantRepository).markAcked(USER_ID, "devices");
        verifyNoMoreInteractions(processedEvents);

    }
}
