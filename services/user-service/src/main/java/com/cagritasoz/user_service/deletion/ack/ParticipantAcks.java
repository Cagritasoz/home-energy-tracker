package com.cagritasoz.user_service.deletion.ack;

import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

// The handler registry: every participant that can confirm a deletion, and how. It answers two questions - "which
// topics must the listener read?" and "is this event a confirmation, and from whom?" - and it is what the required
// set in the configuration is checked against when the application starts (RequiredParticipantsValidator).
//
// Adding a participant is one more entry in ParticipantAcksConfig: the listener subscribes to its topic by itself and
// the name becomes valid in app.user.deletion.required-participants.
public class ParticipantAcks {

    private final List<ParticipantAck> acks;

    public ParticipantAcks(List<ParticipantAck> acks) {

        Set<String> participants = new HashSet<>();
        Set<String> events = new HashSet<>();

        for (ParticipantAck ack : acks) {

            // A name twice would let one participant's event confirm for the other; the same event type on the same
            // topic twice would make the lookup ambiguous.
            if (!participants.add(ack.participant())) {

                throw new IllegalArgumentException("Participant '" + ack.participant() + "' is registered twice");

            }

            if (!events.add(ack.topic() + "/" + ack.eventType())) {

                throw new IllegalArgumentException("Event " + ack.eventType() + " on " + ack.topic() + " is registered twice");

            }

        }

        this.acks = List.copyOf(acks); // No error encountered.

    }

    // The confirmation an event is, if it is one: matched on the topic it arrived on and its event type.
    public Optional<ParticipantAck> find(String topic, String eventType) {

        return acks.stream()
                .filter(ack -> ack.topic().equals(topic) && ack.eventType().equals(eventType))
                .findFirst();

    }

    // The topics the listener subscribes to, each once, in registration order.
    public List<String> topics() {

        Set<String> topics = new LinkedHashSet<>();
        acks.forEach(ack -> topics.add(ack.topic()));

        return List.copyOf(topics);

    }

    public List<String> participants() {

        return acks.stream().map(ParticipantAck::participant).toList();

    }
}
