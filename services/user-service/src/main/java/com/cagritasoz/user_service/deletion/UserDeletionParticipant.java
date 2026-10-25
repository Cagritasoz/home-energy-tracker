package com.cagritasoz.user_service.deletion;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

// A service that must confirm it has removed the user's data before the deletion can finish (V13). The promoter
// writes the rows when the deletion starts; the ack consumer sets acked_at; only a migration sets waived_at.
// Read-only through this entity: the admin view shows who is still missing. The promoter inserts the rows with a
// native query (UserDeletionParticipantRepository.insertRequired) and the ack consumer will update them the same way.
@Entity
@Table(name = "user_deletion_participants")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserDeletionParticipant {

    @EmbeddedId
    private UserDeletionParticipantId id;

    @Column(name = "acked_at")
    private Instant ackedAt;

    @Column(name = "waived_at")
    private Instant waivedAt;
}
