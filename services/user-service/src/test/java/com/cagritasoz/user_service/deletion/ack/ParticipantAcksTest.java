package com.cagritasoz.user_service.deletion.ack;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ParticipantAcksTest {

    private static final ParticipantAck DEVICES = new ParticipantAck("devices", "device.events.v1", "UserDevicesUnclaimed", 1);

    private static final ParticipantAck USAGE = new ParticipantAck("usage", "usage.events.v1", "UserReadingsDeleted", 1);

    private static final ParticipantAck ALERTS = new ParticipantAck("alerts", "device.events.v1", "UserAlertRulesDeleted", 1);

    @Test
    void find_matchesTheTopicAndTheEventType() {

        ParticipantAcks acks = new ParticipantAcks(List.of(DEVICES, USAGE, ALERTS));

        assertThat(acks.find("device.events.v1", "UserDevicesUnclaimed")).contains(DEVICES);
        assertThat(acks.find("usage.events.v1", "UserReadingsDeleted")).contains(USAGE);
        assertThat(acks.find("device.events.v1", "UserAlertRulesDeleted")).contains(ALERTS);

    }

    // The same event type on another topic is not this participant's confirmation, and neither is another type here.
    @Test
    void find_wrongTopicOrWrongType_isEmpty() {

        ParticipantAcks acks = new ParticipantAcks(List.of(DEVICES));

        assertThat(acks.find("usage.events.v1", "UserDevicesUnclaimed")).isEmpty();
        assertThat(acks.find("device.events.v1", "DeviceClaimed")).isEmpty();
        assertThat(acks.find("device.events.v1", "userdevicesunclaimed")).isEmpty();

    }

    @Test
    void topics_listsEachTopicOnceInRegistrationOrder() {

        ParticipantAcks acks = new ParticipantAcks(List.of(DEVICES, USAGE, ALERTS));

        assertThat(acks.topics()).containsExactly("device.events.v1", "usage.events.v1");

    }

    @Test
    void participants_listsEveryName() {

        assertThat(new ParticipantAcks(List.of(DEVICES, USAGE)).participants()).containsExactly("devices", "usage");

    }

    @Test
    void noParticipants_isAllowed() {

        ParticipantAcks acks = new ParticipantAcks(List.of());

        assertThat(acks.topics()).isEmpty();
        assertThat(acks.participants()).isEmpty();
        assertThat(acks.find("device.events.v1", "UserDevicesUnclaimed")).isEmpty();

    }

    // Two entries with one name would let one participant's event confirm for the other.
    @Test
    void constructor_sameParticipantTwice_refuses() {

        ParticipantAck again = new ParticipantAck("devices", "other.events.v1", "SomethingElse", 1);

        assertThatThrownBy(() -> new ParticipantAcks(List.of(DEVICES, again)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("devices");

    }

    @Test
    void constructor_sameEventOnTheSameTopicTwice_refuses() {

        ParticipantAck other = new ParticipantAck("gadgets", "device.events.v1", "UserDevicesUnclaimed", 1);

        assertThatThrownBy(() -> new ParticipantAcks(List.of(DEVICES, other)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("UserDevicesUnclaimed");

    }

    // processed_events keys on (event id, handler): two participants must never share a handler name.
    @Test
    void handler_isUniquePerParticipant() {

        assertThat(DEVICES.handler()).isEqualTo("participant-ack:devices");
        assertThat(USAGE.handler()).isNotEqualTo(DEVICES.handler());

    }
}
