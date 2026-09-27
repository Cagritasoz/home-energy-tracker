package com.cagritasoz.user_service.repository;

import com.cagritasoz.user_service.entity.User;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    @Modifying
    @Query(value = """
            INSERT INTO users (id, email, display_name)
            VALUES (:id, :email, :displayName)
            ON CONFLICT (id) DO NOTHING
            """, nativeQuery = true)
    int insertIgnoringConflict(@Param("id") UUID id,
                       @Param("email") String email,
                       @Param("displayName")  String displayName);

    // No version check, only status = 'ACTIVE': a version check here would make this a silent no-op (no operation)
    // (requestDeletion() would still report success) whenever a concurrent update won the race first.
    // A row lock is released only at commit time of the transaction.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE users
            SET status = 'DELETING',
                deletion_requested_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE id = :id AND status = 'ACTIVE'
            """, nativeQuery = true)
    int markDeleting(@Param("id") UUID id);

    // "clearAutomatically = true": Clear the JPA persistence context after the modifying query. This detaches
    // EVERY entity loaded so far in the transaction, including the User the caller passed in - don't use it afterwards.
    // "flushAutomatically = true": Flush Hibernate's pending changes to the database before executing this query.
    @Modifying(clearAutomatically = true, flushAutomatically = true)
    @Query(value = """
            UPDATE users
            SET email = :email,
                version = version + 1
            WHERE id = :id AND status = 'ACTIVE' AND email <> :email
            """, nativeQuery = true)
    int syncEmail(@Param("id") UUID id, @Param("email") String email);
}
