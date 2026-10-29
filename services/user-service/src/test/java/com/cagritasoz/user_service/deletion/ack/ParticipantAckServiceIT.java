package com.cagritasoz.user_service.deletion.ack;

import com.cagritasoz.user_service.consumer.ProcessedEvents;
import com.cagritasoz.user_service.consumer.ReceivedEvent;
import com.cagritasoz.user_service.testsupport.containers.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.node.JsonNodeFactory;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// A confirmation applied to a real Postgres: what the claim and the participant row do together. Every test runs in
// a rolled-back transaction. That the two commit or roll back as one is proven with real commits in
// ParticipantAckAtomicityIT.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JdbcClientAutoConfiguration.class)
@Import({PostgresTestContainerConfig.class, ParticipantAckService.class, ProcessedEvents.class})
class ParticipantAckServiceIT {

    private static final ParticipantAck DEVICES = new ParticipantAck("devices", "device.events.v1", "UserDevicesUnclaimed", 1);

    private static final ParticipantAck USAGE = new ParticipantAck("usage", "usage.events.v1", "UserReadingsDeleted", 1);

    @Autowired
    private ParticipantAckService service;

    @Autowired
    private JdbcTemplate jdbc;

    private static ReceivedEvent event(UUID eventId, UUID userId, ParticipantAck ack) {

        return new ReceivedEvent(eventId, ack.eventType(), 1, userId.toString(), JsonNodeFactory.instance.objectNode());

    }

    @Test
    void acknowledge_pendingRow_isConfirmedAndTheClaimIsRecorded() {

        UUID user = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        insertDeletingUser(user);
        insertParticipant(user, "devices", false, false);
        insertParticipant(user, "usage", false, false);

        ParticipantAckService.Outcome outcome = service.acknowledge(event(eventId, user, DEVICES), user, DEVICES);

        assertThat(outcome).isEqualTo(ParticipantAckService.Outcome.ACKED);
        assertThat(ackedAtIsSet(user, "devices")).isTrue();

        // Only this participant's row: the other one is still pending.
        assertThat(ackedAtIsSet(user, "usage")).isFalse();

        Map<String, Object> claim = jdbc.queryForMap("SELECT event_id, handler, event_type, aggregate_id FROM processed_events");
        assertThat(claim.get("event_id")).isEqualTo(eventId);
        assertThat(claim.get("handler")).isEqualTo("participant-ack:devices");
        assertThat(claim.get("event_type")).isEqualTo("UserDevicesUnclaimed");
        assertThat(claim.get("aggregate_id")).isEqualTo(user.toString());

    }

    // The users row is never touched: no version bump, no lock contention with a profile update.
    @Test
    void acknowledge_leavesTheUsersRowAlone() {

        UUID user = UUID.randomUUID();
        insertDeletingUser(user);
        insertParticipant(user, "devices", false, false);

        service.acknowledge(event(UUID.randomUUID(), user, DEVICES), user, DEVICES);

        assertThat(jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Long.class, user)).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, user)).isEqualTo("DELETING");

    }

    // A redelivery must not apply the effect again. Proof: put the row back to pending by hand, redeliver the same
    // event, and it stays pending - the claim, not the row's state, is what stopped it.
    @Test
    void acknowledge_sameEventAgain_isNotAppliedAgain() {

        UUID user = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        insertDeletingUser(user);
        insertParticipant(user, "devices", false, false);

        assertThat(service.acknowledge(event(eventId, user, DEVICES), user, DEVICES)).isEqualTo(ParticipantAckService.Outcome.ACKED);

        jdbc.update("UPDATE user_deletion_participants SET acked_at = NULL WHERE user_id = ?", user);

        assertThat(service.acknowledge(event(eventId, user, DEVICES), user, DEVICES))
                .isEqualTo(ParticipantAckService.Outcome.ALREADY_PROCESSED);

        assertThat(ackedAtIsSet(user, "devices")).as("not applied a second time").isFalse();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class)).isOne();

    }

    // The same confirmation published again by a retrying producer has a new event id: nothing is pending any more,
    // which is ignored (and recorded, so this copy is not examined again either).
    @Test
    void acknowledge_newEventForARowAlreadyConfirmed_isIgnored() {

        UUID user = UUID.randomUUID();
        insertDeletingUser(user);
        insertParticipant(user, "devices", false, false);

        service.acknowledge(event(UUID.randomUUID(), user, DEVICES), user, DEVICES);

        UUID secondCopy = UUID.randomUUID();
        assertThat(service.acknowledge(event(secondCopy, user, DEVICES), user, DEVICES))
                .isEqualTo(ParticipantAckService.Outcome.NOT_PENDING);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE event_id = ?", Integer.class, secondCopy)).isOne();

    }

    // A participant that was not required for this deletion (its row was never written) cannot confirm it.
    @Test
    void acknowledge_participantNotRequiredForThisDeletion_isIgnoredAndWritesNoRow() {

        UUID user = UUID.randomUUID();
        insertDeletingUser(user);
        insertParticipant(user, "usage", false, false);

        assertThat(service.acknowledge(event(UUID.randomUUID(), user, DEVICES), user, DEVICES))
                .isEqualTo(ParticipantAckService.Outcome.NOT_PENDING);

        assertThat(jdbc.queryForList("SELECT participant FROM user_deletion_participants WHERE user_id = ?", String.class, user))
                .containsExactly("usage");
        assertThat(ackedAtIsSet(user, "usage")).isFalse();

    }

    // A waived row also holding an acked_at would break the table's check and fail this transaction forever; it is
    // left exactly as the migration made it.
    @Test
    void acknowledge_waivedRow_isLeftWaived() {

        UUID user = UUID.randomUUID();
        insertDeletingUser(user);
        insertParticipant(user, "devices", false, true);

        assertThat(service.acknowledge(event(UUID.randomUUID(), user, DEVICES), user, DEVICES))
                .isEqualTo(ParticipantAckService.Outcome.NOT_PENDING);

        assertThat(ackedAtIsSet(user, "devices")).isFalse();
        assertThat(jdbc.queryForObject("SELECT waived_at IS NOT NULL FROM user_deletion_participants WHERE user_id = ?", Boolean.class, user)).isTrue();

    }

    @Test
    void acknowledge_noSuchDeletion_isIgnored() {

        UUID stranger = UUID.randomUUID();

        assertThat(service.acknowledge(event(UUID.randomUUID(), stranger, DEVICES), stranger, DEVICES))
                .isEqualTo(ParticipantAckService.Outcome.NOT_PENDING);

    }

    // Another user's pending row is not this event's business.
    @Test
    void acknowledge_confirmsOnlyTheUserOfTheEvent() {

        UUID user = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        insertDeletingUser(user);
        insertDeletingUser(other);
        insertParticipant(user, "devices", false, false);
        insertParticipant(other, "devices", false, false);

        service.acknowledge(event(UUID.randomUUID(), user, DEVICES), user, DEVICES);

        assertThat(ackedAtIsSet(user, "devices")).isTrue();
        assertThat(ackedAtIsSet(other, "devices")).isFalse();

    }

    // (event id, handler) is the key: the same event id seen by two participants' handlers is two independent claims.
    @Test
    void acknowledge_sameEventIdForTwoParticipants_isTwoClaims() {

        UUID user = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        insertDeletingUser(user);
        insertParticipant(user, "devices", false, false);
        insertParticipant(user, "usage", false, false);

        assertThat(service.acknowledge(event(eventId, user, DEVICES), user, DEVICES)).isEqualTo(ParticipantAckService.Outcome.ACKED);
        assertThat(service.acknowledge(event(eventId, user, USAGE), user, USAGE)).isEqualTo(ParticipantAckService.Outcome.ACKED);

        assertThat(jdbc.queryForList("SELECT handler FROM processed_events ORDER BY handler", String.class))
                .containsExactly("participant-ack:devices", "participant-ack:usage");

    }

    // ---- helpers ------------------------------------------------------------------------------------

    private boolean ackedAtIsSet(UUID user, String participant) {

        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT acked_at IS NOT NULL FROM user_deletion_participants WHERE user_id = ? AND participant = ?",
                Boolean.class, user, participant));

    }

    private void insertDeletingUser(UUID id) {

        jdbc.update("""
                INSERT INTO users (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for, deletion_started_at)
                VALUES (?, ?, 'Deleted user', 'DELETING',
                        CURRENT_TIMESTAMP - interval '5 hours', CURRENT_TIMESTAMP - interval '5 hours', CURRENT_TIMESTAMP - interval '4 hours')
                """, id, "deleted+" + id + "@invalid");

    }

    private void insertParticipant(UUID userId, String participant, boolean acked, boolean waived) {

        jdbc.update("""
                INSERT INTO user_deletion_participants (user_id, participant, acked_at, waived_at)
                VALUES (?, ?, CASE WHEN ? THEN CURRENT_TIMESTAMP END, CASE WHEN ? THEN CURRENT_TIMESTAMP END)
                """, userId, participant, acked, waived);

    }
}
