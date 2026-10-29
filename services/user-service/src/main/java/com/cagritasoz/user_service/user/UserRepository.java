package com.cagritasoz.user_service.user;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID> {

    // Just-in-time provisioning insert. ON CONFLICT DO NOTHING without a target covers both unique indexes (id and email), and the
    // caller reads the row back to tell them apart: 1 = inserted; 0 and the row with this id exists = a concurrent
    // request created it first; 0 and no row with this id = the email belongs to another account (uq_users_email),
    // which an exception would report just as well but would also abort the caller's transaction, and the caller
    // wants to carry on and ask Keycloak about the other account.
    @Modifying
    @Query(value = """
            INSERT INTO users (id, email, display_name)
            VALUES (:id, :email, :displayName)
            ON CONFLICT DO NOTHING
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

    // The work list of the Keycloak hard-delete job: accounts that are DELETING and whose Keycloak user has not been
    // deleted yet, longest waiting first. A plain read like findIdsDueForPromotion, locking nothing: the job calls
    // Keycloak between this read and the update below, and must not hold a row lock or a connection while it does.
    // Served by idx_users_deleting.
    @Query(value = """
            SELECT id FROM users
            WHERE status = 'DELETING' AND keycloak_deleted_at IS NULL
            ORDER BY deletion_started_at, id
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findIdsPendingKeycloakDeletion(@Param("limit") int limit);

    // Records that the Keycloak user is gone. A compare-and-set like the transitions, so two instances that both
    // deleted the same user (harmless: the delete is idempotent) stamp it once, and the first one is the one that
    // gets 1. It is @Transactional here because it is a single statement and nothing else needs to join it: the
    // Keycloak call that precedes it must happen outside any transaction.
    @Transactional
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE users
            SET keycloak_deleted_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE id = :id AND status = 'DELETING' AND keycloak_deleted_at IS NULL
            """, nativeQuery = true)
    int markKeycloakDeleted(@Param("id") UUID id);

    // The account that holds an email address, if any: the one row uq_users_email lets hold it (lower-cased, not
    // DELETED; a DELETING row has already been scrubbed, so it holds nothing). Used when a registration cannot be
    // provisioned because the address belongs to another account.
    @Query(value = """
            SELECT * FROM users
            WHERE lower(email) = lower(:email) AND status <> 'DELETED'
            """, nativeQuery = true)
    Optional<User> findLiveByEmail(@Param("email") String email);

    // Moves the deadline of an already scheduled deletion to now, so the promoter takes the account on its next
    // tick. For an account that is scheduled but whose Keycloak user is gone: waiting out the rest of the grace period
    // would keep its address blocked for weeks. 0 = it is not scheduled, or already due. No event: the schedule was
    // announced with the original date, and every consumer acts on UserDeletionStarted, not on the date.
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE users
            SET deletion_scheduled_for = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE id = :id AND status = 'DELETION_SCHEDULED' AND deletion_scheduled_for > CURRENT_TIMESTAMP
            """, nativeQuery = true)
    int bringDeletionForward(@Param("id") UUID id);

    // The finalizer's work list: DELETING accounts whose Keycloak user has been gone for the whole quiet period and
    // that no participant is still working for (every participant row is confirmed or waived; an account with no
    // participant rows at all qualifies at once). Longest-gone first. A plain read that locks nothing, like the other
    // work lists; finalizeDeletion below claims each account again. :quiet is an ISO-8601 duration ("PT10M").
    @Query(value = """
            SELECT u.id FROM users u
            WHERE u.status = 'DELETING'
              AND u.keycloak_deleted_at <= CURRENT_TIMESTAMP - CAST(:quiet AS interval)
              AND NOT EXISTS (SELECT 1 FROM user_deletion_participants p
                              WHERE p.user_id = u.id AND p.acked_at IS NULL AND p.waived_at IS NULL)
            ORDER BY u.keycloak_deleted_at, u.id
            LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findIdsReadyForFinalization(@Param("quiet") String quiet, @Param("limit") int limit);

    // DELETING -> DELETED, the last transition. A compare-and-set like the others, and it repeats every condition of
    // the work list, because the list may be stale by the time the row is reached: two finalizers (or a finalizer and
    // anything else that updates the row) serialize on the row lock, and the one that waited re-evaluates its WHERE
    // against the committed row and matches nothing. The participant subquery can only be stale in one direction: a
    // confirmation that commits during the statement is simply not seen yet, so the account is finalized a tick later,
    // never too early (nothing ever turns a confirmed row back into a pending one, and rows are only added at promotion).
    @Modifying(clearAutomatically = true)
    @Query(value = """
            UPDATE users
            SET status = 'DELETED',
                deleted_at = CURRENT_TIMESTAMP,
                version = version + 1
            WHERE id = :id AND status = 'DELETING'
              AND keycloak_deleted_at <= CURRENT_TIMESTAMP - CAST(:quiet AS interval)
              AND NOT EXISTS (SELECT 1 FROM user_deletion_participants p
                              WHERE p.user_id = users.id AND p.acked_at IS NULL AND p.waived_at IS NULL)
            """, nativeQuery = true)
    int finalizeDeletion(@Param("id") UUID id, @Param("quiet") String quiet);

    long countByStatus(UserStatus status);

    // The age of the account that has been DELETING the longest, in seconds; 0 when there is none. A deletion that
    // takes longer than its participants and the quiet period explain is stuck: the watchdog gauge that says so.
    @Query(value = """
            SELECT COALESCE(CAST(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - (
                SELECT min(deletion_started_at) FROM users WHERE status = 'DELETING'))) AS double precision), 0)
            """, nativeQuery = true)
    double oldestDeletingAgeSeconds();

    // The same for the Keycloak delete: how long the longest-waiting DELETING account has been without its Keycloak
    // user deleted, measured from the start of its deletion. Rising while Keycloak is healthy means one account (or the
    // service account's rights) is stuck.
    @Query(value = """
            SELECT COALESCE(CAST(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - (
                SELECT min(deletion_started_at) FROM users
                WHERE status = 'DELETING' AND keycloak_deleted_at IS NULL))) AS double precision), 0)
            """, nativeQuery = true)
    double oldestPendingKeycloakDeleteAgeSeconds();
}
