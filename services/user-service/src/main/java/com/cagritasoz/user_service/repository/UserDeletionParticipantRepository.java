package com.cagritasoz.user_service.repository;

import com.cagritasoz.user_service.entity.UserDeletionParticipant;
import com.cagritasoz.user_service.entity.UserDeletionParticipantId;
import org.springframework.data.jpa.repository.JpaRepository;
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
}
