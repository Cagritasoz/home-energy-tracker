package com.cagritasoz.user_service.repository;


import com.cagritasoz.user_service.entity.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    // ORDER BY seq ASC = oldest first.
    // Parked rows are skipped. The condition matches the partial idx_outbox_events_pending
    // (published_at IS NULL AND NOT parked), which is what lets Postgres use it.
    List<OutboxEvent> findByPublishedAtIsNullAndParkedFalseOrderBySeqAsc(Pageable pageable);

    @Query(value = "SELECT pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    boolean advisoryLockAcquired(@Param("key") long key);

    @Modifying
    @Query(value = """
            UPDATE outbox_events
            SET published_at = CURRENT_TIMESTAMP,
                attempts = attempts + 1
            WHERE seq IN (:seqs)
            """, nativeQuery = true)
    int markPublished(@Param("seqs") List<Long> seqs);

    @Modifying
    @Query(value = """
            UPDATE outbox_events
            SET attempts = attempts + 1,
                last_error = :error
            WHERE seq = :seq
            """, nativeQuery = true)
    void recordError(@Param("error") String error, @Param("seq") Long seq);

    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM outbox_events
            WHERE seq IN (
                SELECT seq FROM outbox_events
                WHERE published_at IS NOT NULL
                  AND published_at < CURRENT_TIMESTAMP - make_interval(days => :retentionDays)
                ORDER BY published_at
                LIMIT :batchSize)
            """, nativeQuery = true)
    int deletePublishedOlderThan(@Param("retentionDays") int retentionDays, @Param("batchSize") int batchSize);

    long countByPublishedAtIsNullAndParkedFalse();

    long countByParkedTrue();

    // LIMIT 1 is what guarantees the oldest row.
    // Returns 0 if there are no pending rows because of COALESCE.
    // EPOCH FROM converts the interval into seconds and CAST casts it as double precision.
    @Query(value = """
            SELECT COALESCE(CAST(EXTRACT(EPOCH FROM (CURRENT_TIMESTAMP - (
                SELECT created_at FROM outbox_events
                WHERE published_at IS NULL AND NOT parked
                ORDER BY seq
                LIMIT 1))) AS double precision), 0)
            """, nativeQuery = true)
    double oldestPendingAgeSeconds();

}
