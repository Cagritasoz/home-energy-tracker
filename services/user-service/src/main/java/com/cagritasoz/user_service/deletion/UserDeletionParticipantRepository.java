package com.cagritasoz.user_service.deletion;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

@Repository
public interface UserDeletionParticipantRepository extends JpaRepository<UserDeletionParticipant, UserDeletionParticipantId> {

    // "IdUserId": the userId property of the embedded id. Ordered by participant name so the admin view is stable.
    List<UserDeletionParticipant> findByIdUserIdOrderByIdParticipant(UUID userId);

    // One query for a whole page of users, instead of one per user.
    List<UserDeletionParticipant> findByIdUserIdIn(Collection<UUID> userIds);

    // Written by the promoter, in the transaction that sets DELETING. DO NOTHING keeps a row that is already there
    // (a migration that added a participant to an in-flight deletion) instead of failing the promotion on it.
    @Modifying
    @Query(value = """
            INSERT INTO user_deletion_participants (user_id, participant)
            VALUES (:userId, :participant)
            ON CONFLICT (user_id, participant) DO NOTHING
            """, nativeQuery = true)
    int insertRequired(@Param("userId") UUID userId, @Param("participant") String participant);

    // A participant's confirmation: 1 = this call confirmed a pending row; 0 = nothing was pending for that user and
    // participant (the participant is not required for this deletion, the row is already confirmed, or it was waived).
    // waived_at IS NULL matters: a waived row that also got an acked_at would break the table's acked-or-waived check
    // and make the ack's transaction fail forever. The users row is not touched.
    @Modifying
    @Query(value = """
            UPDATE user_deletion_participants
            SET acked_at = CURRENT_TIMESTAMP
            WHERE user_id = :userId AND participant = :participant
              AND acked_at IS NULL AND waived_at IS NULL
            """, nativeQuery = true)
    int markAcked(@Param("userId") UUID userId, @Param("participant") String participant);

    // How many deletions still wait for this participant: the watchdog gauge. Served by the partial index on pending rows.
    @Query(value = """
            SELECT count(*) FROM user_deletion_participants
            WHERE participant = :participant AND acked_at IS NULL AND waived_at IS NULL
            """, nativeQuery = true)
    long countPending(@Param("participant") String participant);
}
