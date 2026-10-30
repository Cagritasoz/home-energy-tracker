package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.outbox.OutboxService;
import com.cagritasoz.user_service.profile.UpdateUserRequest;
import com.cagritasoz.user_service.profile.UserProfileService;
import com.cagritasoz.user_service.testsupport.containers.PostgresTestContainerConfig;
import com.cagritasoz.user_service.user.DeletionScheduledException;
import com.cagritasoz.user_service.user.UserResponse;
import com.cagritasoz.user_service.user.UserStatus;
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

// The customer's requests against each other, with real commits on a real Postgres: two DELETEs at once, a PATCH
// racing a schedule in both orders, and two PATCHes at once. These are the races of the design's section 11 that only the request side has.
// The services are the real beans; the controller and the interceptor are not involved (AccountDeletionHttpRaceIT
// runs the same races through HTTP, unstaged).
//
// Nothing here relies on timing luck. Each race is staged: one transaction takes the row lock and is held open, the
// other is started, and the test waits until Postgres itself reports it blocked on that lock (pg_stat_activity), and only
// then is the first one allowed to commit.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({PostgresTestContainerConfig.class, OutboxService.class, DeletionSchedulingService.class, UserProfileService.class})
@TestPropertySource(properties = "app.user.deletion.grace=5m")
@Transactional(propagation = Propagation.NOT_SUPPORTED) // The tests commit for real; no rolled-back test transaction.
class DeletionRequestConcurrencyIT {

    private static final Duration PATIENCE = Duration.ofSeconds(20);

    @Autowired
    private DeletionSchedulingService deletionService;

    @Autowired
    private UserProfileService profileService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcTemplate jdbc;

    private ExecutorService threads;

    @BeforeEach
    void startThreadsAndCleanTables() {

        threads = Executors.newFixedThreadPool(3);
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

    // ---- DELETE against DELETE ------------------------------------------------------------------------

    // Two DELETEs reach an ACTIVE account. The second blocks on the first's row lock; when the first commits, the
    // second's UPDATE matches nothing (the row is no longer ACTIVE), it re-reads and answers the same deadline. One
    // schedule, one event, one version bump, and both callers get 202 with the same date.
    @Test
    void twoSimultaneousDeletes_scheduleOnce_andBothAnswerTheSameDate() throws Exception {

        UUID id = UUID.randomUUID();
        insertActiveUser(id);

        CountDownLatch firstScheduled = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);

        Future<UserResponse> first = threads.submit(() -> inNewTransaction(() -> {
            UserResponse response = deletionService.scheduleDeletion(id);
            firstScheduled.countDown();
            waitFor(commitFirst);
            return response;
        }));
        assertThat(firstScheduled.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).as("first DELETE reached its UPDATE").isTrue();

        Future<UserResponse> second = threads.submit(() -> inNewTransaction(() -> deletionService.scheduleDeletion(id)));
        awaitSomeoneBlockedOnARowLock();

        commitFirst.countDown();

        UserResponse winner = first.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
        UserResponse loser = second.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);

        assertThat(winner.status()).isEqualTo(UserStatus.DELETION_SCHEDULED);
        assertThat(loser.status()).isEqualTo(UserStatus.DELETION_SCHEDULED);
        assertThat(loser.deletionScheduledFor()).as("the same date for both callers").isEqualTo(winner.deletionScheduledFor());
        assertThat(eventTypes()).containsExactly("UserDeletionScheduled");
        assertThat(versionOf(id)).isOne();

    }

    // ---- PATCH against schedule -----------------------------------------------------------------------

    // The schedule holds the row when the PATCH arrives. The PATCH reads the row for update, so it waits for the
    // schedule's commit instead of reading the account while it is still ACTIVE, and then finds it read-only: refused
    // (409 deletion-scheduled), the name unchanged, no UserUpdated written.
    @Test
    void aPatchThatArrivesDuringASchedule_waitsForIt_andIsRefusedBecauseTheAccountIsReadOnly() throws Exception {

        UUID id = UUID.randomUUID();
        insertActiveUser(id);

        CountDownLatch scheduled = new CountDownLatch(1);
        CountDownLatch commitSchedule = new CountDownLatch(1);

        Future<UserResponse> schedule = threads.submit(() -> inNewTransaction(() -> {
            UserResponse response = deletionService.scheduleDeletion(id);
            scheduled.countDown();
            waitFor(commitSchedule);
            return response;
        }));
        assertThat(scheduled.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).as("schedule reached its UPDATE").isTrue();

        Future<UserResponse> patch = threads.submit(() -> profileService.updateUser(id, rename("John Marston")));
        awaitSomeoneBlockedOnARowLock();

        commitSchedule.countDown();
        schedule.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);

        assertThatThrownBy(() -> patch.get(PATIENCE.toSeconds(), TimeUnit.SECONDS))
                .isInstanceOf(ExecutionException.class)
                .hasCauseInstanceOf(DeletionScheduledException.class);

        assertThat(statusOf(id)).isEqualTo("DELETION_SCHEDULED");
        assertThat(displayNameOf(id)).isEqualTo("Arthur Morgan");
        assertThat(eventTypes()).containsExactly("UserDeletionScheduled");
        assertThat(versionOf(id)).isOne();

    }

    // The other order: the PATCH holds the row, and the schedule waits for its lock and goes through after the commit
    // (the schedule checks the status, not the version, so an unrelated edit does not make it a no-op). Both are
    // announced, in the order they committed, with rising versions.
    @Test
    void aPatchThatCommitsFirst_isFollowedByTheSchedule_inOrder() throws Exception {

        UUID id = UUID.randomUUID();
        insertActiveUser(id);

        CountDownLatch patched = new CountDownLatch(1);
        CountDownLatch commitPatch = new CountDownLatch(1);

        Future<UserResponse> patch = threads.submit(() -> inNewTransaction(() -> {
            UserResponse response = profileService.updateUser(id, rename("John Marston"));
            patched.countDown();
            waitFor(commitPatch);
            return response;
        }));
        assertThat(patched.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).as("PATCH reached its UPDATE").isTrue();

        Future<UserResponse> schedule = threads.submit(() -> deletionService.scheduleDeletion(id));
        awaitSomeoneBlockedOnARowLock();

        commitPatch.countDown();

        assertThat(patch.get(PATIENCE.toSeconds(), TimeUnit.SECONDS).displayName()).isEqualTo("John Marston");
        assertThat(schedule.get(PATIENCE.toSeconds(), TimeUnit.SECONDS).status()).isEqualTo(UserStatus.DELETION_SCHEDULED);

        assertThat(displayNameOf(id)).isEqualTo("John Marston");
        assertThat(eventTypes()).containsExactly("UserUpdated", "UserDeletionScheduled");
        assertThat(jdbc.queryForList("SELECT aggregate_version FROM outbox_events ORDER BY seq", Long.class)).containsExactly(1L, 2L);

    }

    // ---- PATCH against PATCH --------------------------------------------------------------------------

    // Two PATCHes at once, each changing a different field. The second waits for the first's lock and reads the row after
    // its commit, so neither change is lost and the second announcement already carries the first one's change.
    @Test
    void twoSimultaneousPatches_serialize_andNeitherChangeIsLost() throws Exception {

        UUID id = UUID.randomUUID();
        insertActiveUser(id);

        CountDownLatch patched = new CountDownLatch(1);
        CountDownLatch commitFirst = new CountDownLatch(1);

        Future<UserResponse> first = threads.submit(() -> inNewTransaction(() -> {
            UserResponse response = profileService.updateUser(id, rename("John Marston"));
            patched.countDown();
            waitFor(commitFirst);
            return response;
        }));
        assertThat(patched.await(PATIENCE.toSeconds(), TimeUnit.SECONDS)).as("first PATCH reached its UPDATE").isTrue();

        Future<UserResponse> second = threads.submit(() ->
                profileService.updateUser(id, UpdateUserRequest.builder().timezone("Europe/Istanbul").build()));
        awaitSomeoneBlockedOnARowLock();

        commitFirst.countDown();
        first.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);

        UserResponse result = second.get(PATIENCE.toSeconds(), TimeUnit.SECONDS);
        assertThat(result.displayName()).as("the second PATCH saw the first one's change").isEqualTo("John Marston");
        assertThat(result.timezone()).isEqualTo("Europe/Istanbul");

        assertThat(displayNameOf(id)).isEqualTo("John Marston");
        assertThat(eventTypes()).containsExactly("UserUpdated", "UserUpdated");
        assertThat(jdbc.queryForList("SELECT aggregate_version FROM outbox_events ORDER BY seq", Long.class)).containsExactly(1L, 2L);
        assertThat(jdbc.queryForList("SELECT payload->'data'->>'displayName' FROM outbox_events ORDER BY seq", String.class))
                .containsExactly("John Marston", "John Marston");

    }

    // ---- helpers ------------------------------------------------------------------------------------

    // Runs the work in a transaction of its own, like a request thread would.
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

    private static UpdateUserRequest rename(String displayName) {

        return UpdateUserRequest.builder().displayName(displayName).build();

    }

    private void insertActiveUser(UUID id) {

        jdbc.update("INSERT INTO users (id, email, display_name) VALUES (?, ?, 'Arthur Morgan')", id, "user." + id + "@example.com");

    }

    private String statusOf(UUID id) {
        return jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, id);
    }

    private String displayNameOf(UUID id) {
        return jdbc.queryForObject("SELECT display_name FROM users WHERE id = ?", String.class, id);
    }

    private Long versionOf(UUID id) {
        return jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Long.class, id);
    }

    private List<String> eventTypes() {
        return jdbc.queryForList("SELECT event_type FROM outbox_events ORDER BY seq", String.class);
    }
}
