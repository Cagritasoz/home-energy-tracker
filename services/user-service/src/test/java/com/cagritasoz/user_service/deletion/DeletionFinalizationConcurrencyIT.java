package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.consumer.ProcessedEvents;
import com.cagritasoz.user_service.consumer.ReceivedEvent;
import com.cagritasoz.user_service.deletion.ack.ParticipantAck;
import com.cagritasoz.user_service.deletion.ack.ParticipantAckService;
import com.cagritasoz.user_service.outbox.OutboxService;
import com.cagritasoz.user_service.testsupport.containers.PostgresTestContainerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Duration;
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

// The finalizer against real concurrency and real commits, on a real Postgres: other finalizers, a participant's
// confirmation arriving while it decides, and a failure in the middle. Same database as DeletionFinalizationIT, which
// is why every test cleans the tables before and after it.
//
// Nothing here relies on timing luck. Each race is staged: one transaction is held open, the other is started, and the
// test waits until Postgres itself reports it blocked on a row lock (pg_stat_activity) - or, where the design says the
// other side must NOT block, the test proves it by finishing that side while the first is still open - and only then is
// the first one allowed to end. The outcome is then the one the design says it must be.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration({JacksonAutoConfiguration.class, JdbcClientAutoConfiguration.class})
@Import({PostgresTestContainerConfig.class, OutboxService.class, DeletionFinalizationService.class,
        ParticipantAckService.class, ProcessedEvents.class})
@TestPropertySource(properties = "app.user.deletion.quiet-period=10m")
@Transactional(propagation = Propagation.NOT_SUPPORTED) // The tests commit for real; no rolled-back test transaction.
class DeletionFinalizationConcurrencyIT {

    private static final Duration PATIENCE = Duration.ofSeconds(20);

    private static final ParticipantAck DEVICES = new ParticipantAck("devices", "device.events.v1", "UserDevicesUnclaimed", 1);

    @Autowired
    private DeletionFinalizationService finalizationService;

    @Autowired
    private ParticipantAckService ackService;

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

        jdbc.execute("TRUNCATE TABLE processed_events, outbox_events, user_deletion_participants, users RESTART IDENTITY CASCADE");

    }

    // ---- finalizer against finalizer ----------------------------------------------------------------------

    // Two finalizers reach the same ready account. The second blocks on the first's row lock and, after the commit,
    // finds the row DELETED: one finalization, one event, one version bump.
    @Test
    void twoFinalizersOnTheSameAccount_finalizeItOnce() throws Exception {

        UUID id = UUID.randomUUID();
        insertDeletingUser(id);

        CountDownLatch firstFinalized = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);

        Future<Boolean> first = threads.submit(() -> inNewTransaction(() -> {
            boolean result = finalizationService.finalizeDeletion(id);
            firstFinalized.countDown();
            waitFor(commitFirst);
            return result;
        }));
        assertThat(firstFinalized.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).as("first finalizer reached its UPDATE").isTrue();

        Future<Boolean> second = threads.submit(() -> inNewTransaction(() -> finalizationService.finalizeDeletion(id)));
        awaitSomeoneBlockedOnARowLock();

        commitFirst.countDown();

        assertThat(first.get(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();
        assertThat(second.get(PATIENCE.toSeconds(), TimeUnit.SECONDS)).as("the second finalizer matched nothing").isFalse();
        assertThat(eventTypes()).containsExactly("UserDeletionFinalized");
        assertThat(jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Long.class, id)).isOne();
        assertThat(statusOf(id)).isEqualTo("DELETED");

    }

    // Two whole jobs (two instances of the service, as in a rolling deploy) work the same backlog at the same time,
    // with no staging at all: whatever the interleaving, every account is finalized exactly once.
    @Test
    void twoJobsOnTheSameBacklog_finalizeEveryAccountExactlyOnce() throws Exception {

        int accounts = 40;
        for (int i = 0; i < accounts; i++) {
            insertDeletingUser(UUID.randomUUID());
        }

        SimpleMeterRegistry metersA = new SimpleMeterRegistry();
        SimpleMeterRegistry metersB = new SimpleMeterRegistry();
        DeletionFinalizationJob jobA = new DeletionFinalizationJob(finalizationService, metersA, 7);
        DeletionFinalizationJob jobB = new DeletionFinalizationJob(finalizationService, metersB, 7);
        CountDownLatch gate = new CountDownLatch(1);

        Future<?> a = threads.submit(() -> drain(jobA, gate));
        Future<?> b = threads.submit(() -> drain(jobB, gate));
        gate.countDown();
        a.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
        b.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE status = 'DELETED'", Integer.class)).isEqualTo(accounts);
        assertThat(jdbc.queryForObject("""
                SELECT count(*) FROM (SELECT aggregate_id FROM outbox_events WHERE event_type = 'UserDeletionFinalized'
                                      GROUP BY aggregate_id HAVING count(*) = 1) once
                """, Integer.class)).isEqualTo(accounts);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM outbox_events", Integer.class)).isEqualTo(accounts);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE version = 1", Integer.class)).isEqualTo(accounts);

        double finalizedByA = metersA.get("user.deletion.finalized").counter().count();
        double finalizedByB = metersB.get("user.deletion.finalized").counter().count();
        assertThat(finalizedByA + finalizedByB).as("finalizations counted by the two instances together").isEqualTo(accounts);
        assertThat(metersA.get("user.deletion.finalization.failures").counter().count()).isZero();
        assertThat(metersB.get("user.deletion.finalization.failures").counter().count()).isZero();

    }

    private void drain(DeletionFinalizationJob job, CountDownLatch gate) {

        waitFor(gate);
        while (!finalizationService.findReady(7).isEmpty()) {
            job.finalizeReady();
        }

    }

    // ---- confirmation against finalizer -------------------------------------------------------------------

    // The last confirmation is written but not committed yet when the finalizer decides. The finalizer does not wait
    // for it (it reads, it does not lock the participant rows) and does not see it: the account is not finalized this
    // time. Once the confirmation commits, the next finalization goes through. Late, never early.
    @Test
    void aConfirmationNotCommittedYet_isNotSeenAndDoesNotBlockTheFinalizer_theNextTickFinalizes() throws Exception {

        UUID id = UUID.randomUUID();
        insertDeletingUser(id);
        insertPendingParticipant(id, "devices");

        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch commitConfirmation = new CountDownLatch(1);

        Future<ParticipantAckService.Outcome> confirmation = threads.submit(() -> inNewTransaction(() -> {
            ParticipantAckService.Outcome outcome = ackService.acknowledge(event(UUID.randomUUID(), id), id, DEVICES);
            written.countDown();
            waitFor(commitConfirmation);
            return outcome;
        }));
        assertThat(written.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).as("confirmation reached its UPDATE").isTrue();

        // Run on another thread so a finalizer that wrongly waited would fail this test by timeout instead of hanging it.
        Future<Boolean> early = threads.submit(() -> finalizationService.finalizeDeletion(id));
        assertThat(early.get(PATIENCE.toSeconds(), TimeUnit.SECONDS)).as("finalizer decided without the confirmation").isFalse();
        assertThat(finalizationService.findReady(10)).isEmpty();
        assertThat(statusOf(id)).isEqualTo("DELETING");
        assertThat(eventTypes()).isEmpty();

        commitConfirmation.countDown();
        assertThat(confirmation.get(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isEqualTo(ParticipantAckService.Outcome.ACKED);

        assertThat(finalizationService.findReady(10)).containsExactly(id);
        assertThat(finalizationService.finalizeDeletion(id)).isTrue();
        assertThat(eventTypes()).containsExactly("UserDeletionFinalized");

    }

    // A confirmation that was written and then rolled back (its transaction failed after the update) never existed:
    // not for the finalizer while it was open, and not after. The claim goes with it, so the redelivery is applied for real.
    @Test
    void aConfirmationThatRollsBack_isNeverSeenByTheFinalizer() throws Exception {

        UUID id = UUID.randomUUID();
        insertDeletingUser(id);
        insertPendingParticipant(id, "devices");

        CountDownLatch written = new CountDownLatch(1);
        CountDownLatch fail = new CountDownLatch(1);

        Future<?> confirmation = threads.submit(() -> inNewTransaction(() -> {
            ackService.acknowledge(event(UUID.randomUUID(), id), id, DEVICES);
            written.countDown();
            waitFor(fail);
            throw new IllegalStateException("the transaction fails after the update");
        }));
        assertThat(written.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();

        assertThat(finalizationService.finalizeDeletion(id)).as("while the confirmation is open").isFalse();

        fail.countDown();
        assertThatThrownBy(() -> confirmation.get(PATIENCE.toSeconds(), TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasRootCauseInstanceOf(IllegalStateException.class);

        assertThat(finalizationService.finalizeDeletion(id)).as("after it rolled back").isFalse();
        assertThat(finalizationService.findReady(10)).isEmpty();
        assertThat(jdbc.queryForObject("SELECT acked_at IS NULL FROM user_deletion_participants WHERE user_id = ?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class)).as("the claim went with it").isZero();
        assertThat(statusOf(id)).isEqualTo("DELETING");
        assertThat(eventTypes()).isEmpty();

    }

    // A confirmation touches the participant row and nothing else: it never waits for whoever holds the users row (a
    // profile write, a finalizer, a job), and never holds it for anyone. Here another transaction has updated the row
    // and not committed; the confirmation finishes anyway.
    @Test
    void aConfirmation_isNotBlockedByAnotherWriterOfTheUsersRow() throws Exception {

        UUID id = UUID.randomUUID();
        insertDeletingUser(id);
        insertPendingParticipant(id, "devices");

        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);

        Future<?> writer = threads.submit(() -> inNewTransaction(() -> {
            jdbc.update("UPDATE users SET timezone = 'Europe/Istanbul' WHERE id = ?", id);
            holding.countDown();
            waitFor(release);
            return null;
        }));
        assertThat(holding.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).as("writer holds the users row").isTrue();

        Future<ParticipantAckService.Outcome> confirmation = threads.submit(() ->
                inNewTransaction(() -> ackService.acknowledge(event(UUID.randomUUID(), id), id, DEVICES)));

        // Finished while the writer is still open: it did not wait for the users row.
        assertThat(confirmation.get(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isEqualTo(ParticipantAckService.Outcome.ACKED);

        release.countDown();
        writer.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);

        assertThat(finalizationService.finalizeDeletion(id)).isTrue();

    }

    // ---- all or nothing ----------------------------------------------------------------------------------

    // Finalization is one transaction through the real service bean: if the last step (the event) fails, the status
    // change and the deletion stamp go with it, and the account stays DELETING, ready again on the next tick. The
    // failure is made by a trigger on the outbox table, which stands in for any error from the database at that point.
    @Test
    void finalizationWhoseEventCannotBeWritten_rollsBackTheWholeFinalization() {

        UUID id = UUID.randomUUID();
        insertDeletingUser(id);

        jdbc.execute("""
                CREATE FUNCTION fail_outbox_insert() RETURNS trigger LANGUAGE plpgsql AS
                $$ BEGIN RAISE EXCEPTION 'outbox is broken'; END $$
                """);
        jdbc.execute("CREATE TRIGGER trg_fail_outbox_insert BEFORE INSERT ON outbox_events "
                + "FOR EACH ROW EXECUTE FUNCTION fail_outbox_insert()");

        try {

            assertThatThrownBy(() -> finalizationService.finalizeDeletion(id)).isInstanceOf(RuntimeException.class);

        }
        finally {

            jdbc.execute("DROP TRIGGER trg_fail_outbox_insert ON outbox_events");
            jdbc.execute("DROP FUNCTION fail_outbox_insert()");

        }

        assertThat(statusOf(id)).isEqualTo("DELETING");
        assertThat(jdbc.queryForObject("SELECT deleted_at IS NULL FROM users WHERE id = ?", Boolean.class, id)).isTrue();
        assertThat(jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Long.class, id)).isZero();
        assertThat(finalizationService.findReady(10)).containsExactly(id);

    }

    // ---- helpers ------------------------------------------------------------------------------------

    // Runs the work in a transaction of its own, like a scheduler thread or a consumer thread would.
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

    // Postgres reports a statement waiting for another transaction's row lock as wait_event 'transactionid'.
    private void awaitSomeoneBlockedOnARowLock() {

        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(50)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("""
                        SELECT count(*) FROM pg_stat_activity
                        WHERE datname = current_database() AND wait_event_type = 'Lock' AND wait_event = 'transactionid'
                        """, Integer.class)).isPositive());

    }

    // A DELETING account as the promoter and the Keycloak job leave it, long past the quiet period (10 minutes here).
    private void insertDeletingUser(UUID id) {

        jdbc.update("""
                INSERT INTO users (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for,
                                   deletion_started_at, keycloak_deleted_at)
                VALUES (?, ?, 'Deleted user', 'DELETING',
                        CURRENT_TIMESTAMP - interval '5 hours', CURRENT_TIMESTAMP - interval '5 hours',
                        CURRENT_TIMESTAMP - interval '4 hours', CURRENT_TIMESTAMP - interval '1 hour')
                """, id, "deleted+" + id + "@invalid");

    }

    private void insertPendingParticipant(UUID userId, String participant) {

        jdbc.update("INSERT INTO user_deletion_participants (user_id, participant) VALUES (?, ?)", userId, participant);

    }

    private static ReceivedEvent event(UUID eventId, UUID userId) {

        return new ReceivedEvent(eventId, DEVICES.eventType(), 1, userId.toString(), JsonNodeFactory.instance.objectNode());

    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, id);
    }

    private List<String> eventTypes() {
        return jdbc.queryForList("SELECT event_type FROM outbox_events ORDER BY seq", String.class);
    }
}
