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

    // ACTIVE -> DELETION_SCHEDULED, a compare-and-set: only the request that finds the row ACTIVE changes it and
    // gets 1; a concurrent or repeated request gets 0. No version check, only the status: a version check here
    // would make this a silent no-op whenever an unrelated update (a PATCH) won the race first.
    //
    // The deadline is computed here, by the database, from its own clock: an absolute time, so changing the
    // configured grace period later moves no existing deadline. :grace is an ISO-8601 duration ("PT5M",
    // Duration.toString()), which Postgres reads as an interval. A row lock is released only at commit time.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE users
            SET status = 'DELETION_SCHEDULED',
                deletion_requested_at = CURRENT_TIMESTAMP,
                deletion_scheduled_for = CURRENT_TIMESTAMP + CAST(:grace AS interval),
                version = version + 1
            WHERE id = :id AND status = 'ACTIVE'
            """, nativeQuery = true)
    int scheduleDeletion(@Param("id") UUID id, @Param("grace") String grace);

    // DELETION_SCHEDULED -> ACTIVE, only while the deadline lies ahead: the time comparison decides who may still
    // cancel, the row lock and the status check decide who wins against the promoter (whichever commits first;
    // the other sees 0). The three deletion columns go back to NULL, which the table's checks require of an ACTIVE row.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE users
            SET status = 'ACTIVE',
                deletion_requested_at = NULL,
                deletion_scheduled_for = NULL,
                version = version + 1
            WHERE id = :id AND status = 'DELETION_SCHEDULED' AND deletion_scheduled_for > CURRENT_TIMESTAMP
            """, nativeQuery = true)
    int cancelDeletion(@Param("id") UUID id);

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
