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
    void insertIgnoringConflict(@Param("id") UUID id,
                       @Param("email") String email,
                       @Param("displayName")  String displayName);

    @Modifying
    @Query(value = """
            UPDATE users
            SET status = 'DELETING',
                deletion_requested_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE id = :id AND status = 'ACTIVE'
            """, nativeQuery = true)
    int markDeleting(@Param("id") UUID id);

    @Modifying
    @Query(value = """
            UPDATE users
            SET email = :email,
                version = version + 1
            WHERE id = :id AND status = 'ACTIVE' AND email <> :email
            """, nativeQuery = true)
    void syncEmail(@Param("id") UUID id, @Param("email") String email);
}
