package com.cagritasoz.user_service.deletion;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

import java.io.Serializable;
import java.util.UUID;

// The primary key of user_deletion_participants (V13): one row per user and participating service.
@Embeddable
public record UserDeletionParticipantId(

        @Column(name = "user_id")
        UUID userId,

        @Column(name = "participant")
        String participant

) implements Serializable {
}
