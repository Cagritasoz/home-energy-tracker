package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.outbox.OutboxService;
import com.cagritasoz.user_service.testsupport.containers.PostgresTestContainerConfig;
import com.cagritasoz.user_service.user.AccountDeletedException;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

// The promoter against real concurrency and real commits, on a real Postgres. Same context (and so the same
// database) as DeletionPromotionIT, which is why every test cleans the tables before and after it.
//
// Nothing here relies on timing luck. Each race is staged: one transaction takes the row lock and is held open,
// the other is started and the test waits until Postgres itself reports it blocked on that lock (pg_stat_activity),
// and only then is the first one allowed to commit. The outcome is then the one the design says it must be.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({PostgresTestContainerConfig.class, OutboxService.class, DeletionPromotionService.class, DeletionSchedulingService.class})
@TestPropertySource(properties = {
        "app.user.deletion.required-participants=devices,usage",
        "app.user.deletion.grace=5m"
})
@Transactional(propagation = Propagation.NOT_SUPPORTED) // The tests commit for real; no rolled-back test transaction.
class DeletionPromotionConcurrencyIT {

    private static final Duration PATIENCE = Duration.ofSeconds(20);

    @Autowired
    private DeletionPromotionService promotionService;

    @Autowired
    private DeletionSchedulingService deletionService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService threads;

    @BeforeEach
    void startThreadsAndCleanTables() {

        threads = Executors.newFixedThreadPool(4);
        clean();

    }

    @AfterEach
    void stopThreadsAndCleanTables() {

        threads.shutdownNow();
        clean();

    }

    private void clean() {

        jdbc.execute("TRUNCATE TABLE outbox_events, user_deletion_participants, users RESTART IDENTITY CASCADE");

    }

    // ---- cancel against promote -----------------------------------------------------------------------

    // The cancel got the row lock first (and its transaction started inside the grace period). The promoter, started
    // after the deadline, blocks on the row; when the cancel commits, the promoter's WHERE no longer matches (the
    // row is ACTIVE) and it skips the account. The user's last-moment cancel wins.
    @Test
    void cancelHoldingTheRowLock_beatsAPromoterThatArrivesAfterTheDeadline() throws Exception {

        UUID id = UUID.randomUUID();
        insertScheduledUser(id, "CURRENT_TIMESTAMP + interval '5 seconds'");

        CountDownLatch cancelDone = new CountDownLatch(1);
        CountDownLatch commitCancel = new CountDownLatch(1);

        Future<?> cancel = threads.submit(() -> inNewTransaction(() -> {
            deletionService.cancelDeletion(id);
            cancelDone.countDown();
            // Hold transaction from commiting
            waitFor(commitCancel);
            return null;
        }));
        assertThat(cancelDone.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).as("cancel reached its UPDATE").isTrue();

        awaitDeadlineOf(id);

        Future<Boolean> promote = threads.submit(() -> inNewTransaction(() -> promotionService.promote(id)));

        // cancel work still holds the row, assert that row is locked.
        awaitSomeoneBlockedOnARowLock();

        // Release transaction.
        commitCancel.countDown();
        cancel.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);

        assertThat(promote.get(PATIENCE.toSeconds(), TimeUnit.SECONDS)).as("promoter skipped the account").isFalse();
        assertThat(statusOf(id)).isEqualTo("ACTIVE");
        assertThat(eventTypes()).containsExactly("UserDeletionCancelled");
        assertThat(participantCount()).isZero();

    }

    // The other order: the promoter holds the row lock. A cancel that began inside the grace period (its clock was
    // fixed before the deadline, so by time alone it would be allowed) blocks, and when the promoter commits the
    // row is DELETING: the cancel finds nothing to cancel and the caller is told the account is gone (410).
    @Test
    void promoterHoldingTheRowLock_beatsACancelThatBeganInsideTheGracePeriod() throws Exception {

        UUID id = UUID.randomUUID();
        insertScheduledUser(id, "CURRENT_TIMESTAMP + interval '5 seconds'");

        CountDownLatch clockFixed = new CountDownLatch(1);
        CountDownLatch cancelNow = new CountDownLatch(1);
        CountDownLatch promoted = new CountDownLatch(1);
        CountDownLatch commitPromotion = new CountDownLatch(1);

        // CURRENT_TIMESTAMP is the start of the transaction, so one statement fixes this cancel's "now" inside the
        // grace period; the cancel itself is issued later, after the deadline.
        Future<?> cancel = threads.submit(() -> inNewTransaction(() -> {
            jdbc.execute("SELECT 1");
            clockFixed.countDown();
            waitFor(cancelNow);
            deletionService.cancelDeletion(id);
            return null;
        }));
        assertThat(clockFixed.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();

        awaitDeadlineOf(id);

        Future<Boolean> promote = threads.submit(() -> inNewTransaction(() -> {
            boolean result = promotionService.promote(id);
            promoted.countDown();
            waitFor(commitPromotion);
            return result;
        }));
        assertThat(promoted.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).as("promoter reached its UPDATE").isTrue();

        cancelNow.countDown();
        awaitSomeoneBlockedOnARowLock();

        commitPromotion.countDown();

        assertThat(promote.get(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();
        assertThatThrownBy(() -> cancel.get(PATIENCE.toSeconds(), TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(AccountDeletedException.class);
        assertThat(statusOf(id)).isEqualTo("DELETING");
        assertThat(eventTypes()).containsExactly("UserDeletionStarted");
        assertThat(participantsOf(id)).containsExactly("devices", "usage");

    }

    // ---- promote against promote ----------------------------------------------------------------------

    // Two promoters reach the same account. The second blocks on the first's row lock and, after the commit, finds
    // the row DELETING: one promotion, one event, one set of participant rows.
    @Test
    void twoPromotersOnTheSameAccount_promoteItOnce() throws Exception {

        UUID id = UUID.randomUUID();
        insertScheduledUser(id, "CURRENT_TIMESTAMP - interval '1 minute'");

        CountDownLatch firstPromoted = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);

        Future<Boolean> first = threads.submit(() -> inNewTransaction(() -> {
            boolean result = promotionService.promote(id);
            firstPromoted.countDown();
            waitFor(commitFirst);
            return result;
        }));
        assertThat(firstPromoted.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();

        Future<Boolean> second = threads.submit(() -> inNewTransaction(() -> promotionService.promote(id)));
        awaitSomeoneBlockedOnARowLock();

        commitFirst.countDown();

        assertThat(first.get(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();
        assertThat(second.get(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isFalse();
        assertThat(eventTypes()).containsExactly("UserDeletionStarted");
        assertThat(participantsOf(id)).containsExactly("devices", "usage");
        assertThat(jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Long.class, id)).isOne();

    }

    // Two whole jobs (two instances of the service, as in a rolling deploy) work the same backlog at the same time,
    // with no staging at all: whatever the interleaving, every account is promoted exactly once.
    @Test
    void twoJobsOnTheSameBacklog_promoteEveryAccountExactlyOnce() throws Exception {

        int accounts = 40;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < accounts; i++) {
            UUID id = UUID.randomUUID();
            ids.add(id);
            insertScheduledUser(id, "CURRENT_TIMESTAMP - interval '1 minute'");
        }

        SimpleMeterRegistry metersA = new SimpleMeterRegistry();
        SimpleMeterRegistry metersB = new SimpleMeterRegistry();
        DeletionPromotionJob jobA = new DeletionPromotionJob(promotionService, metersA, 7);
        DeletionPromotionJob jobB = new DeletionPromotionJob(promotionService, metersB, 7);
        CountDownLatch gate = new CountDownLatch(1);

        Future<?> a = threads.submit(() -> drain(jobA, gate));
        Future<?> b = threads.submit(() -> drain(jobB, gate));
        // Release gate
        gate.countDown();
        a.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
        b.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE status = 'DELETING'", Integer.class)).isEqualTo(accounts);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT aggregate_id FROM outbox_events WHERE event_type = 'UserDeletionStarted'
                                      GROUP BY aggregate_id HAVING count(*) = 1) once
                """, Integer.class)).isEqualTo(accounts);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(accounts);
        assertThat(participantCount()).isEqualTo(accounts * 2);

        double promotedByA = metersA.get("user.deletion.promoted").counter().count();
        double promotedByB = metersB.get("user.deletion.promoted").counter().count();
        assertThat(promotedByA + promotedByB).as("promotions counted by the two instances together").isEqualTo(accounts);
        assertThat(metersA.get("user.deletion.promotion.failures").counter().count()).isZero();
        assertThat(metersB.get("user.deletion.promotion.failures").counter().count()).isZero();

    }

    private void drain(DeletionPromotionJob job, CountDownLatch gate) {

        waitFor(gate);
        while (!promotionService.findDue(7).isEmpty()) {
            job.promoteDue();
        }

    }

    // ---- all or nothing ----------------------------------------------------------------------------------

    // The promotion is one transaction through the real service bean: if the last step (the event) fails, the status
    // change, the scrub and the participant rows all go with it, and the account stays DELETION_SCHEDULED, intact and
    // due again on the next tick. The failure is made by a trigger on the outbox table, which stands in for any error
    // from the database at that point.
    @Test
    void promoteWhoseEventCannotBeWritten_rollsBackTheWholePromotion() {

        UUID id = UUID.randomUUID();
        insertScheduledUser(id, "CURRENT_TIMESTAMP - interval '1 minute'");

        jdbc.execute("""
                CREATE FUNCTION fail_outbox_insert() RETURNS trigger LANGUAGE plpgsql AS
                $$ BEGIN RAISE EXCEPTION 'outbox is broken'; END $$
                """);
        jdbc.execute("CREATE TRIGGER trg_fail_outbox_insert BEFORE INSERT ON outbox_events "
                + "FOR EACH ROW EXECUTE FUNCTION fail_outbox_insert()");

        try {

            assertThatThrownBy(() -> promotionService.promote(id)).isInstanceOf(RuntimeException.class);

        }
        finally {

            jdbc.execute("DROP TRIGGER trg_fail_outbox_insert ON outbox_events");
            jdbc.execute("DROP FUNCTION fail_outbox_insert()");

        }

        assertThat(statusOf(id)).isEqualTo("DELETION_SCHEDULED");
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, id)).isEqualTo(addressOf(id));
        assertThat(jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Long.class, id)).isZero();
        assertThat(participantCount()).isZero();
        assertThat(promotionService.findDue(10)).containsExactly(id);

    }

    // ---- helpers ------------------------------------------------------------------------------------

    // Runs the work in a transaction of its own, like a scheduler thread or a request thread would.
    private <T> T inNewTransaction(Supplier<T> work) {

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        return template.execute(status -> work.get());

    }

    private static void waitFor(CountDownLatch latch) {

        try {

            if (!latch.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)) {

                throw new IllegalStateException("the test never released a staged transaction");

            }

        }
        catch (InterruptedException e) {

            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);

        }

    }

    // The deadline is the database's clock; wait until the database agrees it has passed.
    private void awaitDeadlineOf(UUID id) {

        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(100)).until(() ->
                Boolean.TRUE.equals(jdbc.queryForObject(
                        "SELECT CURRENT_TIMESTAMP > deletion_scheduled_for FROM users WHERE id = ?", Boolean.class, id)));

    }

    // Postgres reports a statement waiting for another transaction's row lock as wait_event 'transactionid'.
    private void awaitSomeoneBlockedOnARowLock() {

        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE datname = current_database() AND wait_event_type = 'Lock' AND wait_event = 'transactionid'
                        """, Integer.class)).isPositive());

    }

    // deadline is a SQL expression on the database's clock, so "5 seconds ahead" and "a minute ago" mean the same
    // thing to the test and to the queries under test.
    private void insertScheduledUser(UUID id, String deadline) {

        jdbc.update("""
                INSERT INTO users (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for)
                VALUES (?, ?, 'Arthur Morgan', 'DELETION_SCHEDULED', CURRENT_TIMESTAMP - interval '2 minutes', %s)
                """.formatted(deadline), id, addressOf(id));

    }

    private static String addressOf(UUID id) {
        return "user." + id + "@example.com";
    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, id);
    }

    private List<String> eventTypes() {
        return jdbc.queryForList("SELECT event_type FROM outbox_events ORDER BY seq", String.class);
    }

    private List<String> participantsOf(UUID id) {
        return jdbc.queryForList("SELECT participant FROM user_deletion_participants WHERE user_id = ? ORDER BY participant",
                String.class, id);
    }

    private Integer participantCount() {
        return jdbc.queryForObject("SELECT count(*) FROM user_deletion_participants", Integer.class);
    }
}
