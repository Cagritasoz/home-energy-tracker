package com.cagritasoz.user_service.deletion;

import jakarta.persistence.Column;
import jakarta.persistence.EmbeddedId;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.ToString;

import java.time.Instant;

// A service that must confirm it has removed the user's data before the deletion can finish (V4). The promoter
// writes the rows when the deletion starts; the ack consumer sets acked_at; only a migration sets waived_at.
// Read-only through this entity: the admin view shows who is still missing. The promoter inserts the rows with a
// native query (UserDeletionParticipantRepository.insertRequired) and the ack consumer will update them the same way.
@Entity
@Table(name = "user_deletion_participants")
@Getter
@Builder
@NoArgsConstructor
@AllArgsConstructor
// No setters: the entity is read-only. Identity is the composite id (a record, so it compares by value); the confirmation
// times are state, and a row's hash must not change with them.
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString
public class UserDeletionParticipant {

    @EmbeddedId
    @EqualsAndHashCode.Include
    private UserDeletionParticipantId id;

    @Column(name = "acked_at")
    private Instant ackedAt;

    @Column(name = "waived_at")
    private Instant waivedAt;
}
