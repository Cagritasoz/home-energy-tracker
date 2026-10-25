package com.cagritasoz.user_service.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
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

    // The promoter's work list: scheduled accounts whose grace period is over, oldest deadline first. A plain read
    // that locks nothing; every row is claimed again, by its own compare-and-set, in promoteDeletion. Served by
    // the partial index idx_users_deletion_scheduled.
    @Query(value = """
            SELECT id FROM users
            WHERE status = 'DELETION_SCHEDULED' AND deletion_scheduled_for <= CURRENT_TIMESTAMP
            ORDER BY deletion_scheduled_for, id
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findIdsDueForPromotion(@Param("limit") int limit);

    // DELETION_SCHEDULED -> DELETING, the moment the deletion becomes irreversible. A compare-and-set like the
    // other transitions, and it is also what keeps the promoter and a cancel apart: both UPDATE the same row, so
    // whichever gets the row lock first commits, and the other one - blocked until then - re-evaluates its WHERE
    // against the committed row and finds 0 rows to change. The deadline is checked again here (the list above
    // may be stale by the time the row is reached).
    //
    // The personal data goes in the same statement as the status, so no committed row is DELETING and still holds
    // an address: the email becomes deleted+<id>@invalid (unique per id, so it never collides in uq_users_email and
    // never blocks a real address), the display name a fixed text. timezone is not personal enough to scrub.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE users
            SET status = 'DELETING',
                email = 'deleted+' || id || '@invalid',
                display_name = 'Deleted user',
                deletion_started_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE id = :id AND status = 'DELETION_SCHEDULED' AND deletion_scheduled_for <= CURRENT_TIMESTAMP
            """, nativeQuery = true)
    int promoteDeletion(@Param("id") UUID id);
}
