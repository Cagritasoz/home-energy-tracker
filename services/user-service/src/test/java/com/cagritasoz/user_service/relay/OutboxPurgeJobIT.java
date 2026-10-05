package com.cagritasoz.user_service.relay;

import com.cagritasoz.user_service.repository.OutboxRepository;
import com.cagritasoz.user_service.testsupport.JdbcParameters;
import com.cagritasoz.user_service.testsupport.PostgresTestContainerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

// The purge job over the real repository: the loop and the delete statement together. The job is built by
// hand with a batch size of 2, so a handful of rows needs several batches (the application's batch size is
// 1000).
//
// @DataJpaTest is one transaction, so this does not prove that every batch commits on its own; it proves
// that the loop keeps going until the eligible rows are gone and touches nothing else. CURRENT_TIMESTAMP is
// the transaction's start time, so the rows are seeded relative to the database clock read once at the start.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
class OutboxPurgeJobIT {

    private static final int RETENTION_DAYS = 7;

    private static final int BATCH_SIZE = 2;

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void purge_oldPublishedRows_areDeletedAcrossSeveralBatchesAndNothingElseIsTouched() {

        Instant dbNow = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Instant.class);
        // Five eligible rows: three batches of 2 + 2 + 1.
        for (int days = 8; days <= 12; days++) {
            assert dbNow != null;
            insertEvent(null, dbNow.minus(days, ChronoUnit.DAYS), false);
        }
        long recentPublished = insertEvent(null, dbNow.minus(1, ChronoUnit.DAYS), false);
        long oldPending = insertEvent(dbNow.minus(30, ChronoUnit.DAYS), null, false); // never published: not eligible
        long parked = insertEvent(dbNow.minus(30, ChronoUnit.DAYS), null, true);

        new OutboxPurgeJob(outboxRepository, RETENTION_DAYS, BATCH_SIZE).purge();

        assertThat(remainingSeqs()).containsExactly(recentPublished, oldPending, parked);

    }

    @Test
    void purge_runTwice_theSecondRunDeletesNothing() {

        Instant dbNow = jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Instant.class);
        assert dbNow != null;
        insertEvent(null, dbNow.minus(9, ChronoUnit.DAYS), false);
        long recentPublished = insertEvent(null, dbNow.minus(1, ChronoUnit.DAYS), false);
        OutboxPurgeJob purgeJob = new OutboxPurgeJob(outboxRepository, RETENTION_DAYS, BATCH_SIZE);

        purgeJob.purge();
        purgeJob.purge();

        assertThat(remainingSeqs()).containsExactly(recentPublished);

    }

    // created_at null = now; published_at null = pending. A parked row cannot be published (V8 CHECK).
    private Long insertEvent(Instant createdAt, Instant publishedAt, boolean parked) {

        return jdbc.queryForObject("""
                        INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, topic, payload,
                                                   occurred_at, created_at, published_at, parked)
                        VALUES (?, 'User', ?, 'UserRegistered', 'user.events.v1', '{}'::jsonb,
                                CURRENT_TIMESTAMP, COALESCE(CAST(? AS timestamptz), CURRENT_TIMESTAMP),
                                CAST(? AS timestamptz), ?)
                        RETURNING seq
                        """,
                Long.class,
                JdbcParameters.bindable(UUID.randomUUID(), UUID.randomUUID().toString(), createdAt, publishedAt, parked));

    }

    private List<Long> remainingSeqs() {

        return jdbc.queryForList("SELECT seq FROM outbox_events ORDER BY seq", Long.class);

    }
}
