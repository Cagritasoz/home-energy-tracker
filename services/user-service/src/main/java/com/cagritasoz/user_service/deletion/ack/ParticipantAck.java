package com.cagritasoz.user_service.deletion.ack;

// One participant service's way of confirming that it has finished its part of a deletion: which event it sends, on
// which topic, and in which shape version. "participant" is the name that appears in
// app.user.deletion.required-participants and in user_deletion_participants.participant.
public record ParticipantAck(
        String participant,
        String topic,
        String eventType,
        int schemaVersion
) {

    // The name processed_events records for this handler. One handler per participant, so two participants can never
    // claim each other's events.
    public String handler() {
        return "participant-ack:" + participant;
    }
}
