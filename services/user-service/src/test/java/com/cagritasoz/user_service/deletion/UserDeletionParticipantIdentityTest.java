package com.cagritasoz.user_service.deletion;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class UserDeletionParticipantIdentityTest {

    private UserDeletionParticipant.UserDeletionParticipantBuilder row(UUID userId, String participant) {
        return UserDeletionParticipant.builder().id(new UserDeletionParticipantId(userId, participant));
    }

    @Test
    void equals_sameUserAndParticipant_isEqualWhateverTheConfirmationTimes() {
        UUID userId = UUID.randomUUID();
        UserDeletionParticipant pending = row(userId, "devices").build();
        UserDeletionParticipant acked = row(userId, "devices").ackedAt(Instant.parse("2026-01-01T00:00:00Z")).build();

        assertThat(pending).isEqualTo(acked).hasSameHashCodeAs(acked);
    }

    @Test
    void equals_otherParticipantOrOtherUser_isNotEqual() {
        UUID userId = UUID.randomUUID();

        assertThat(row(userId, "devices").build()).isNotEqualTo(row(userId, "usage").build());
        assertThat(row(userId, "devices").build()).isNotEqualTo(row(UUID.randomUUID(), "devices").build());
    }
}
