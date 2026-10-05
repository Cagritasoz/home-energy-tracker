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
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

// The SQL the relay, the purge job and the metrics depend on, against a real Postgres.
//
// @DataJpaTest runs each test in ONE transaction that is rolled back at the end. CURRENT_TIMESTAMP in
// Postgres is the transaction's start time, so inside a test it never moves: every query below sees the
// same "now". Tests read that clock once (dbNow()) and seed rows relative to it, which makes ages and
// retention boundaries exact instead of "close to".
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import(PostgresTestContainerConfig.class)
class OutboxRepositoryIT {

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbc;

    // ---- the relay's batch query -------------------------------------------------------------------

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

    // ---- counts (metrics gauges) -------------------------------------------------------------------

    @Test
    void countPending_mixedRows_countsOnlyUnpublishedUnparkedRows() {

        Instant dbNow = dbNow();
        seed(null, null, false, 0);
        seed(null, null, false, 0);
        seed(null, dbNow, false, 1);  // published
        seed(null, null, true, 0);    // parked

        assertThat(outboxRepository.countByPublishedAtIsNullAndParkedFalse()).isEqualTo(2);

    }

    @Test
    void countPending_emptyTable_isZero() {

        assertThat(outboxRepository.countByPublishedAtIsNullAndParkedFalse()).isZero();

    }

    @Test
    void countParked_onlyParkedRowsAreCounted() {

        seed(null, null, false, 0);
        seed(null, dbNow(), false, 1);
        seed(null, null, true, 0);

        assertThat(outboxRepository.countByParkedTrue()).isEqualTo(1);

    }

    @Test
    void countParked_noParkedRows_isZero() {

        seed(null, null, false, 0);

        assertThat(outboxRepository.countByParkedTrue()).isZero();

    }

    // ---- oldestPendingAgeSeconds (the stall alarm) -------------------------------------------------
    //
    // Age = database now - created_at of the pending row with the LOWEST seq, i.e. the head of the queue, the
    // row the relay is actually stuck on. created_at is set explicitly here so the age is known exactly.

    @Test
    void oldestPendingAge_emptyTable_isZero() {

        assertThat(outboxRepository.oldestPendingAgeSeconds()).isZero();

    }

    @Test
    void oldestPendingAge_onlyPublishedAndParkedRows_isZero() {

        Instant dbNow = dbNow();
        seed(dbNow.minusSeconds(900), dbNow, false, 1);
        seed(dbNow.minusSeconds(900), null, true, 0);

        assertThat(outboxRepository.oldestPendingAgeSeconds()).isZero();

    }

    @Test
    void oldestPendingAge_onePendingRow_isItsAgeInSeconds() {

        seed(dbNow().minusSeconds(300), null, false, 0);

        assertThat(outboxRepository.oldestPendingAgeSeconds()).isCloseTo(300.0, within(0.001));

    }

    @Test
    void oldestPendingAge_usesTheHeadOfTheQueueNotTheOldestCreatedAt() {

        // The first row in the queue (lowest seq) is younger than the one behind it. The relay is stuck on the
        // first one, so its age is what the alarm must report: 60 s, not 300 s.
        Instant dbNow = dbNow();
        seed(dbNow.minusSeconds(60), null, false, 0);
        seed(dbNow.minusSeconds(300), null, false, 0);

        assertThat(outboxRepository.oldestPendingAgeSeconds()).isCloseTo(60.0, within(0.001));

    }

    @Test
    void oldestPendingAge_aParkedRowAtTheHead_isSkipped() {

        Instant dbNow = dbNow();
        seed(dbNow.minusSeconds(500), null, true, 0);   // parked, lowest seq, oldest
        seed(dbNow.minusSeconds(100), null, false, 0);

        assertThat(outboxRepository.oldestPendingAgeSeconds()).isCloseTo(100.0, within(0.001));

    }

    @Test
    void oldestPendingAge_anOlderPublishedRow_isIgnored() {

        Instant dbNow = dbNow();
        seed(dbNow.minusSeconds(900), dbNow, false, 1); // published, lowest seq, oldest
        seed(dbNow.minusSeconds(10), null, false, 0);

        assertThat(outboxRepository.oldestPendingAgeSeconds()).isCloseTo(10.0, within(0.001));

    }

    // ---- markPublished -----------------------------------------------------------------------------

    @Test
    void markPublished_givenSeqs_marksOnlyThemAndCountsTheAttempt() {

        Instant dbNow = dbNow();
        Seeded first = seed(null, null, false, 0);
        Seeded second = seed(null, null, false, 2);   // already tried twice
        Seeded notListed = seed(null, null, false, 0);

        int updated = outboxRepository.markPublished(List.of(first.seq(), second.seq()));

        assertThat(updated).isEqualTo(2);
        assertThat(publishedAt(first.seq())).isEqualTo(dbNow);
        assertThat(attempts(first.seq())).isEqualTo(1);
        assertThat(publishedAt(second.seq())).isEqualTo(dbNow);
        assertThat(attempts(second.seq())).isEqualTo(3); // incremented from its current value
        assertThat(publishedAt(notListed.seq())).isNull();
        assertThat(attempts(notListed.seq())).isZero();

    }

    // ---- recordError -------------------------------------------------------------------------------

    @Test
    void recordError_failedRow_countsTheAttemptKeepsTheErrorAndStaysPending() {

        Seeded failed = seed(null, null, false, 0);
        Seeded other = seed(null, null, false, 0);

        outboxRepository.recordError("org.apache.kafka.common.errors.TimeoutException: Expiring 1 record(s)", failed.seq());

        assertThat(attempts(failed.seq())).isEqualTo(1);
        assertThat(lastError(failed.seq())).isEqualTo("org.apache.kafka.common.errors.TimeoutException: Expiring 1 record(s)");
        assertThat(publishedAt(failed.seq())).isNull();
        assertThat(attempts(other.seq())).isZero();
        assertThat(lastError(other.seq())).isNull();

    }

    @Test
    void recordError_calledTwice_overwritesTheErrorAndCountsBothAttempts() {

        Seeded failed = seed(null, null, false, 0);

        outboxRepository.recordError("first error", failed.seq());
        outboxRepository.recordError("second error", failed.seq());

        assertThat(attempts(failed.seq())).isEqualTo(2);
        assertThat(lastError(failed.seq())).isEqualTo("second error");

    }

    // ---- deletePublishedOlderThan (the purge job's statement) --------------------------------------

    @Test
    void deletePublishedOlderThan_deletesOldPublishedRowsAndKeepsEverythingElse() {

        Instant dbNow = dbNow();
        Seeded oldPublished = seed(null, dbNow.minus(8, ChronoUnit.DAYS), false, 1);
        Seeded recentPublished = seed(null, dbNow.minus(1, ChronoUnit.DAYS), false, 1);
        Seeded oldPending = seed(dbNow.minus(30, ChronoUnit.DAYS), null, false, 0); // old, but never published
        Seeded parked = seed(dbNow.minus(30, ChronoUnit.DAYS), null, true, 0);

        int deleted = outboxRepository.deletePublishedOlderThan(7, 1000);

        assertThat(deleted).isEqualTo(1);
        assertThat(remainingSeqs()).containsExactly(recentPublished.seq(), oldPending.seq(), parked.seq());
        assertThat(remainingSeqs()).doesNotContain(oldPublished.seq());

    }

    @Test
    void deletePublishedOlderThan_exactlyAtTheRetention_keepsTheRowOneSecondOlderGoes() {

        // Strict "older than": published_at = now - 7 days is kept, one second older is deleted. Both
        // timestamps are measured from the same database "now", so the boundary is exact.
        Instant dbNow = dbNow();
        Seeded atTheLimit = seed(null, dbNow.minus(7, ChronoUnit.DAYS), false, 1);
        Seeded justOver = seed(null, dbNow.minus(7, ChronoUnit.DAYS).minusSeconds(1), false, 1);

        int deleted = outboxRepository.deletePublishedOlderThan(7, 1000);

        assertThat(deleted).isEqualTo(1);
        assertThat(remainingSeqs()).containsExactly(atTheLimit.seq());
        assertThat(remainingSeqs()).doesNotContain(justOver.seq());

    }

    @Test
    void deletePublishedOlderThan_moreEligibleRowsThanTheBatch_deletesTheOldestFirstAndInBatches() {

        // Published-at order is deliberately NOT seq order, so ORDER BY published_at (and not seq) is what
        // makes the 12- and 11-day-old rows go first.
        Instant dbNow = dbNow();
        Seeded nineDays = seed(null, dbNow.minus(9, ChronoUnit.DAYS), false, 1);
        Seeded twelveDays = seed(null, dbNow.minus(12, ChronoUnit.DAYS), false, 1);
        Seeded eightDays = seed(null, dbNow.minus(8, ChronoUnit.DAYS), false, 1);
        Seeded elevenDays = seed(null, dbNow.minus(11, ChronoUnit.DAYS), false, 1);
        Seeded tenDays = seed(null, dbNow.minus(10, ChronoUnit.DAYS), false, 1);

        assertThat(outboxRepository.deletePublishedOlderThan(7, 2)).isEqualTo(2);
        assertThat(remainingSeqs()).containsExactly(nineDays.seq(), eightDays.seq(), tenDays.seq());
        assertThat(remainingSeqs()).doesNotContain(twelveDays.seq(), elevenDays.seq());

        assertThat(outboxRepository.deletePublishedOlderThan(7, 2)).isEqualTo(2);
        assertThat(remainingSeqs()).containsExactly(eightDays.seq());

        assertThat(outboxRepository.deletePublishedOlderThan(7, 2)).isEqualTo(1);
        assertThat(remainingSeqs()).isEmpty();

        assertThat(outboxRepository.deletePublishedOlderThan(7, 2)).isZero();

    }

    @Test
    void deletePublishedOlderThan_nothingEligible_returnsZero() {

        seed(null, dbNow().minus(1, ChronoUnit.DAYS), false, 1);
        seed(null, null, false, 0);

        assertThat(outboxRepository.deletePublishedOlderThan(7, 1000)).isZero();

    }

    // ---- helpers -----------------------------------------------------------------------------------

    private record Seeded(long seq, UUID id) {
    }

    // The database's idea of "now" for this transaction: constant for the whole test (see the class comment).
    private Instant dbNow() {

        return jdbc.queryForObject("SELECT CURRENT_TIMESTAMP", Instant.class);

    }

    // Inserts a row with explicit created_at / published_at (null = created now / not published) and returns its
    // seq. A parked row cannot be published (V8 CHECK), so never pass both.
    private Seeded seed(Instant createdAt, Instant publishedAt, boolean parked, int attempts) {

        UUID id = UUID.randomUUID();

        Long seq = jdbc.queryForObject("""
                        INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, topic, payload,
                                                   occurred_at, created_at, published_at, parked, attempts)
                        VALUES (?, 'User', ?, 'UserRegistered', 'user.events.v1', '{}'::jsonb,
                                CURRENT_TIMESTAMP, COALESCE(CAST(? AS timestamptz), CURRENT_TIMESTAMP),
                                CAST(? AS timestamptz), ?, ?)
                        RETURNING seq
                        """,
                Long.class,
                JdbcParameters.bindable(id, UUID.randomUUID().toString(), createdAt, publishedAt, parked, attempts));

        return new Seeded(seq, id);

    }

    private List<Long> remainingSeqs() {

        return jdbc.queryForList("SELECT seq FROM outbox_events ORDER BY seq", Long.class);

    }

    private Instant publishedAt(long seq) {

        return jdbc.queryForObject("SELECT published_at FROM outbox_events WHERE seq = ?", Instant.class, seq);

    }

    private Integer attempts(long seq) {

        return jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE seq = ?", Integer.class, seq);

    }

    private String lastError(long seq) {

        return jdbc.queryForObject("SELECT last_error FROM outbox_events WHERE seq = ?", String.class, seq);

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
