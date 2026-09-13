package com.cagritasoz.user_service.repository;

import com.cagritasoz.user_service.entity.OutboxEvent;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface OutboxEventRepository extends JpaRepository<OutboxEvent, Long> {

    // Backed by V4's partial index (idx_outbox_events_pending) - stays fast regardless of how
    // large the published history grows. Pageable bounds the batch size per relay tick instead of
    // pulling an unbounded backlog in one go after downtime. Asc -> the oldest first.
    List<OutboxEvent> findByPublishedAtIsNullOrderByIdAsc(Pageable pageable);
}
