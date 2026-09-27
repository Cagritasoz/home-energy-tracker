package com.cagritasoz.user_service.repository;


import com.cagritasoz.user_service.entity.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxRepository extends JpaRepository<OutboxEvent, Long> {

    // ORDER BY seq ASC = oldest first.
    List<OutboxEvent> findByPublishedAtIsNullOrderBySeqAsc(Pageable pageable);

    @Query(value = "SELECT pg_try_advisory_xact_lock(:key)", nativeQuery = true)
    boolean advisoryLockAcquired(@Param("key") long key);

    @Modifying
    @Query(value = """
            UPDATE outbox_events
            SET published_at = CURRENT_TIMESTAMP,
                attempts = attempts + 1
            WHERE seq = :seq
            """, nativeQuery = true)
    int markPublished(@Param("seq") Long seq);

    @Modifying
    @Query(value = """
            UPDATE outbox_events
            SET attempts = attempts + 1,
                last_error = :error
            WHERE seq = :seq
            """, nativeQuery = true)
    void recordError(@Param("error") String error, @Param("seq") Long seq);
}
