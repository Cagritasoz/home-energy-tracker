package com.cagritasoz.user_service.outbox;

import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class OutboxEventIdentityTest {

    private static final String PAYLOAD = "{\"data\":{\"email\":\"arthur.morgan@example.com\",\"displayName\":\"Arthur Morgan\"}}";

    private OutboxEvent.OutboxEventBuilder event(UUID id) {
        return OutboxEvent.builder()
                .id(id)
                .aggregateType("User")
                .aggregateId(UUID.randomUUID().toString())
                .eventType("UserRegistered")
                .topic("user.events.v1")
                .payload(PAYLOAD);
    }

    @Test
    void equals_sameEventId_isEqualWhateverTheOtherFieldsHold() {
        UUID id = UUID.randomUUID();
        OutboxEvent first = event(id).seq(1L).build();
        OutboxEvent second = event(id).seq(2L).attempts(5).parked(true).lastError("boom").build();

        assertThat(first).isEqualTo(second).hasSameHashCodeAs(second);
    }

    @Test
    void equals_differentEventId_isNotEqual() {
        assertThat(event(UUID.randomUUID()).build()).isNotEqualTo(event(UUID.randomUUID()).build());
    }

    @Test
    void hashCode_survivesTheRelayRecordingItsWork() {
        OutboxEvent event = event(UUID.randomUUID()).build();
        Set<OutboxEvent> set = new HashSet<>(Set.of(event));
        int before = event.hashCode();

        event.setSeq(42L);
        event.setAttempts(3);
        event.setLastError("timeout");
        event.setParked(true);

        assertThat(event.hashCode()).isEqualTo(before);
        assertThat(set).contains(event);
    }

    @Test
    void toString_neverContainsThePayload() {
        OutboxEvent event = event(UUID.randomUUID()).seq(9L).build();

        assertThat(event.toString())
                .contains(event.getId().toString(), "UserRegistered", "9")
                .doesNotContain("arthur.morgan@example.com", "Arthur Morgan");
    }
}
