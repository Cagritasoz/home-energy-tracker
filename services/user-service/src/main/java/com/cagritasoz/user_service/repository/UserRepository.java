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

    // Compare-and-set on status: the WHERE clause is what makes this safe under concurrency. Two
    // simultaneous requests both try to update the row, Postgres lets one go first and makes the
    // other wait on the row lock; once the first commits, the second re-checks status = 'ACTIVE',
    // no longer matches, and updates 0 rows. So exactly one caller gets 1 back, and no lost update
    // is possible - no version check is needed for that.
    // The timestamp comes from the database clock (same source as created_at/updated_at), not
    // from the application. Native SQL bypasses Hibernate, so @Version does nothing here: the
    // version is bumped by hand to keep it moving for any Hibernate write that loaded the row
    // earlier (its own version check then fails instead of silently overwriting this change).
    // updated_at is stamped by the V6 trigger, as for every other UPDATE.
    // Returns the number of rows changed: 1 = the account is now DELETING because of this call,
    // 0 = no such user, or it was not ACTIVE anymore. An entity loaded earlier in the same
    // persistence context is not refreshed by this statement and keeps its old status/version.
    @Modifying
    @Query(value = """
            UPDATE users
            SET status = 'DELETING',
                deletion_requested_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE id = :id AND status = 'ACTIVE'
            """, nativeQuery = true)
    int markDeleting(@Param("id") UUID id);
}
