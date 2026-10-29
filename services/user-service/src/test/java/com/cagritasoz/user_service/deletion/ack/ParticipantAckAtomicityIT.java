package com.cagritasoz.user_service.deletion.ack;

import com.cagritasoz.user_service.consumer.ProcessedEvents;
import com.cagritasoz.user_service.consumer.ReceivedEvent;
import com.cagritasoz.user_service.testsupport.containers.PostgresTestContainerConfig;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.autoconfigure.JdbcClientAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.node.JsonNodeFactory;

import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;

// The two guarantees of the consumer that rolled-back test transactions cannot show, proven with real commits:
//
//   1. The claim in processed_events and the update of the participant row are one unit. When anything after the claim
//      fails, the claim goes with it, and the retry applies the event for real. (If the claim survived, the retry would
//      find it, skip the event as "already applied", and the deletion would wait for a confirmation that was thrown away.)
//   2. Two copies of the same event delivered at the same time are applied once: the second one waits for the first
//      one's claim and then finds it.
//
// Each race is staged, not left to timing: one transaction is held open, the other is started, and the test waits until
// Postgres itself reports the second blocked on a lock before the first is allowed to commit.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JdbcClientAutoConfiguration.class)
@Import({PostgresTestContainerConfig.class, ParticipantAckService.class, ProcessedEvents.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED) // The tests commit for real; no rolled-back test transaction.
class ParticipantAckAtomicityIT {

    private static final ParticipantAck DEVICES = new ParticipantAck("devices", "device.events.v1", "UserDevicesUnclaimed", 1);

    private static final Duration PATIENCE = Duration.ofSeconds(20);

    @Autowired
    private ParticipantAckService service;

    @Autowired
    private ProcessedEvents processedEvents;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService threads;

    @BeforeEach
    void startThreadsAndCleanTables() {

        threads = Executors.newFixedThreadPool(2);
        clean();

    }

    @AfterEach
    void stopThreadsAndCleanTables() {

        threads.shutdownNow();
        clean();

    }

    private void clean() {

        jdbc.execute("TRUNCATE TABLE processed_events, user_deletion_participants, users RESTART IDENTITY CASCADE");

    }

    private TransactionTemplate newTransaction() {

        TransactionTemplate template = new TransactionTemplate(transactionManager);
        template.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);

        return template;

    }

    private static ReceivedEvent event(UUID eventId, UUID userId) {

        return new ReceivedEvent(eventId, DEVICES.eventType(), 1, userId.toString(), JsonNodeFactory.instance.objectNode());

    }

    private void insertDeletingUserWithPendingDevices(UUID id) {

        jdbc.update("""
                INSERT INTO users (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for, deletion_started_at)
                VALUES (?, ?, 'Deleted user', 'DELETING',
                        CURRENT_TIMESTAMP - interval '5 hours', CURRENT_TIMESTAMP - interval '5 hours', CURRENT_TIMESTAMP - interval '4 hours')
                """, id, "deleted+" + id + "@invalid");
        jdbc.update("INSERT INTO user_deletion_participants (user_id, participant) VALUES (?, 'devices')", id);

    }

    private boolean ackedAtIsSet(UUID user) {

        return Boolean.TRUE.equals(jdbc.queryForObject(
                "SELECT acked_at IS NOT NULL FROM user_deletion_participants WHERE user_id = ? AND participant = 'devices'",
                Boolean.class, user));

    }

    private Integer claims() { // claim count.

        return jdbc.queryForObject("SELECT count(*) FROM processed_events", Integer.class);

    }

    // ---- 1. all or nothing --------------------------------------------------------------------------

    @Test
    void failureAfterTheClaim_rollsTheClaimBackWithTheAck_andTheRetryAppliesTheEvent() {

        UUID user = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        insertDeletingUserWithPendingDevices(user);

        // The service committed nothing of its own here: it joins this transaction, which then fails.
        assertThatThrownBy(() -> newTransaction().executeWithoutResult(status -> {

            assertThat(service.acknowledge(event(eventId, user), user, DEVICES)).isEqualTo(ParticipantAckService.Outcome.ACKED);

            throw new IllegalStateException("the listener failed after the effect"); // rollback

        })).isInstanceOf(IllegalStateException.class);

        assertThat(claims()).as("the claim went with the failed transaction").isZero();
        assertThat(ackedAtIsSet(user)).as("and so did the ack").isFalse();

        // The redelivery: the event is applied for real.
        assertThat(service.acknowledge(event(eventId, user), user, DEVICES)).isEqualTo(ParticipantAckService.Outcome.ACKED);

        assertThat(claims()).isOne();
        assertThat(ackedAtIsSet(user)).isTrue();

    }

    // A claim made outside the effect's transaction would survive the effect's failure: refused.
    @Test
    void claim_outsideATransaction_isRefused() {

        assertThatThrownBy(() -> processedEvents.claim(UUID.randomUUID(), "participant-ack:devices", "UserDevicesUnclaimed", "x"))
                .isInstanceOf(IllegalTransactionStateException.class);

        assertThat(claims()).isZero();

    }

    // ---- 2. two copies at once ----------------------------------------------------------------------

    @Test
    void sameEventDeliveredTwiceAtOnce_isAppliedOnce() throws Exception {

        UUID user = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();
        insertDeletingUserWithPendingDevices(user);

        CountDownLatch firstHasClaimed = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);

        // The first delivery claims and confirms, then holds its transaction open.
        Future<ParticipantAckService.Outcome> first = threads.submit(() -> newTransaction().execute(status -> {

            ParticipantAckService.Outcome outcome = service.acknowledge(event(eventId, user), user, DEVICES);

            firstHasClaimed.countDown();
            awaitLatch(commitFirst);

            return outcome;

        }));

        assertThat(firstHasClaimed.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isTrue();

        // The second delivery of the same event is started and must block on the first one's uncommitted claim.
        Future<ParticipantAckService.Outcome> second = threads.submit(() ->
                newTransaction().execute(status -> service.acknowledge(event(eventId, user), user, DEVICES)));

        await().atMost(PATIENCE).pollInterval(Duration.ofMillis(50)).until(() -> jdbc.queryForObject(
                "SELECT count(*) FROM pg_stat_activity WHERE datname = current_database() AND wait_event_type = 'Lock'",
                Integer.class) >= 1);
        assertThat(second.isDone()).as("the second delivery waits for the first").isFalse();

        commitFirst.countDown();

        assertThat(first.get(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isEqualTo(ParticipantAckService.Outcome.ACKED);
        assertThat(second.get(PATIENCE.toSeconds(), TimeUnit.SECONDS)).isEqualTo(ParticipantAckService.Outcome.ALREADY_PROCESSED);

        assertThat(claims()).isOne();
        assertThat(ackedAtIsSet(user)).isTrue();

    }

    private static void awaitLatch(CountDownLatch latch) {

        try {

            if (!latch.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)) {

                throw new IllegalStateException("The test never released the transaction");

            }

        }
        catch (InterruptedException e) {

            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);

        }

    }
}
