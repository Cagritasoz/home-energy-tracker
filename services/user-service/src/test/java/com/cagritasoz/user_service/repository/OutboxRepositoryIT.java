package com.cagritasoz.user_service.repository;

import com.cagritasoz.user_service.entity.OutboxEvent;
import com.cagritasoz.user_service.testsupport.PostgresTestContainerConfig;
import com.cagritasoz.user_service.testsupport.JdbcParameters;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageRequest;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
class OutboxRepositoryIT {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void findPendingBatch_mixedRows_returnsOnlyUnpublishedUnparkedRowsInSeqOrder() {

        UUID firstPending = insertEvent(false, false);
        insertEvent(true, false);
        insertEvent(false, true);
        UUID secondPending = insertEvent(false, false);

        List<OutboxEvent> batch = outboxRepository.findByPublishedAtIsNullAndParkedFalseOrderBySeqAsc(PageRequest.of(0, 10));

        assertThat(batch)
                .extracting(OutboxEvent::getId)
                .containsExactly(firstPending, secondPending);

    }

    @Test
    void findPendingBatch_moreRowsThanBatchSize_returnsTheOldestOnly() {

        UUID oldest = insertEvent(false, false);
        UUID second = insertEvent(false, false);
        insertEvent(false, false);

        List<OutboxEvent> batch = outboxRepository.findByPublishedAtIsNullAndParkedFalseOrderBySeqAsc(PageRequest.of(0, 2));

        assertThat(batch)
                .extracting(OutboxEvent::getId)
                .containsExactly(oldest, second);

    }

    private UUID insertEvent(boolean published, boolean parked) {

        UUID id = UUID.randomUUID();

        jdbc.update("""
                        INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, topic, payload, occurred_at, published_at, parked)
                        VALUES (?, 'User', ?, 'UserRegistered', 'user.events.v1', '{}'::jsonb, CURRENT_TIMESTAMP, ?, ?)
                        """,
                JdbcParameters.bindable(id, UUID.randomUUID().toString(), published ? Instant.now() : null, parked));

        return id;

    }
}
