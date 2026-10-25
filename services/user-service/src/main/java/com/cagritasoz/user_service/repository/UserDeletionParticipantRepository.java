package com.cagritasoz.user_service.repository;

import com.cagritasoz.user_service.entity.UserDeletionParticipant;
import com.cagritasoz.user_service.entity.UserDeletionParticipantId;
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
}
