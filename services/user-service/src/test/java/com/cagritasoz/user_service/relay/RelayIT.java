package com.cagritasoz.user_service.relay;

import com.cagritasoz.contracts.EventHeaders;
import com.github.dockerjava.api.command.PauseContainerCmd;
import com.github.dockerjava.api.command.UnpauseContainerCmd;
import com.cagritasoz.contracts.user.UserEvents;
import com.cagritasoz.user_service.entity.OutboxEvent;
import com.cagritasoz.user_service.repository.OutboxRepository;
import com.cagritasoz.user_service.testsupport.KafkaTestContainerConfig;
import com.cagritasoz.user_service.testsupport.PostgresTestContainerConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// The relay against a real Postgres and a real Kafka broker (Testcontainers), with nothing mocked:
// what actually lands in the topic, and what the outbox table says about it afterwards.
//
// This is not a full application context. The scheduler is deliberately left out (no @EnableScheduling
// here), so the tests call publishBatch()/relay() themselves and decide exactly when a "tick" happens -
// a real @Scheduled relay running in the background would race with every assertion.
//
// Every test uses its own topic, so records left over from an earlier test never show up in a later
// one, and every test starts with an empty outbox table.
@ExtendWith(OutputCaptureExtension.class)

// SpringBootTest means "Use this class as the source of the Spring Boot test application.", class body is empty the annotations are doing the work.
// webEnvironment = SpringBootTest.WebEnvironment.NONE means "Don't start a web server."
@SpringBootTest(classes = RelayIT.RelayTestApplication.class, webEnvironment = SpringBootTest.WebEnvironment.NONE)
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class RelayIT {

    // The relay's advisory-lock key is a literal inside OutboxBatchPublisher; the lock test takes the same one.
    private static final long RELAY_LOCK_KEY = 7_777_777L;

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EntityScan(basePackageClasses = OutboxEvent.class)
    @EnableJpaRepositories(basePackageClasses = OutboxRepository.class)
    @Import({OutboxBatchPublisher.class, RelayKafkaConfig.class})
    static class RelayTestApplication {
    }

    @Autowired
    private OutboxBatchPublisher publisher; // the real bean: @Transactional proxy, real KafkaTemplate

    @Autowired
    private OutboxRepository outboxRepository;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private KafkaContainer kafka;

    @BeforeEach
    void cleanOutbox() {

        jdbc.update("DELETE FROM outbox_events");

    }

    // ---- the happy path ----------------------------------------------------------------------------

    @Test
    void publishBatch_pendingRows_reachKafkaWithKeyHeadersAndPayloadAndAreMarkedPublished() {

        String topic = createTopic(3);
        UUID correlationId = UUID.randomUUID();
        // Three users, two events each, interleaved: seq 1..6.
        List<Seeded> rows = new ArrayList<>();
        String[] users = {uuid(), uuid(), uuid()};
        for (int i = 0; i < 6; i++) {
            rows.add(insertEvent(topic, users[i % 3], i == 0 ? correlationId : null)); // i%3 = 0, 1, 2, 0, 1, 2, only one event gets non-null correlationId.
        }

        BatchResult result = publisher.publishBatch();

        assertThat(result.outcome()).isEqualTo(BatchResult.Outcome.PUBLISHED);
        assertThat(result.published()).isEqualTo(6);
        assertThat(pendingSeqs()).isEmpty();

        List<ConsumerRecord<String, String>> records = consume(topic, 6, Duration.ofSeconds(20));
        assertThat(records).hasSize(6);

        for (Seeded row : rows) {
            ConsumerRecord<String, String> record = recordOf(records, row);
            assertThat(record.key()).isEqualTo(row.aggregateId());
            assertThat(header(record, EventHeaders.EVENT_ID)).isEqualTo(row.eventId().toString());
            assertThat(header(record, EventHeaders.EVENT_TYPE)).isEqualTo("UserUpdated");
            assertThat(header(record, EventHeaders.SCHEMA_VERSION)).isEqualTo("1");
        }

        // Only the row that had a correlation id carries the header.
        assertThat(header(recordOf(records, rows.get(0)), EventHeaders.CORRELATION_ID)).isEqualTo(correlationId.toString());
        assertThat(recordOf(records, rows.get(1)).headers().lastHeader(EventHeaders.CORRELATION_ID)).isNull();

        // One aggregate always lands in one partition, and its events are in seq order inside it.
        Map<String, List<ConsumerRecord<String, String>>> byUser = new LinkedHashMap<>();

        // Create the list of ConsumerRecords per user, if userId key is absent create the arraylist and add the record, if not add the record to the existing list.
        records.forEach(r -> byUser.computeIfAbsent(r.key(), k -> new ArrayList<>()).add(r));

        byUser.forEach((user, userRecords) -> {

            // getFirst() since each record per user shares the same partition anyway.
            assertThat(userRecords).extracting(ConsumerRecord::partition).containsOnly(userRecords.getFirst().partition());

            assertThat(userRecords).extracting(r -> header(r, EventHeaders.EVENT_ID))
                    .containsExactlyElementsOf(rows.stream().filter(s -> s.aggregateId().equals(user))
                            .map(s -> s.eventId().toString()).toList());
        });

        // The bookkeeping: published once, one attempt, no error.
        List<Map<String, Object>> table = jdbc.queryForList(
                "SELECT published_at IS NOT NULL AS published, attempts, last_error FROM outbox_events");
        assertThat(table).hasSize(6).allSatisfy(row -> {
            assertThat(row.get("published")).isEqualTo(true);
            assertThat(row.get("attempts")).isEqualTo(1);
            assertThat(row.get("last_error")).isNull();
        });

    }

    @Test
    void relay_backlogLargerThanOneBatch_isDrainedInOneTick() {

        String topic = createTopic(3);
        for (int i = 0; i < 120; i++) {
            insertEvent(topic, uuid(), null);
        }
        SimpleMeterRegistry meters = new SimpleMeterRegistry();
        OutboxRelay relay = new OutboxRelay(publisher, meters, 10, 4_000, 30_000, System::nanoTime);

        relay.relay(); // 120 rows, batch size 50: three batches, one tick

        assertThat(pendingSeqs()).isEmpty();
        assertThat(consume(topic, 120, Duration.ofSeconds(30))).hasSize(120);
        assertThat(meters.counter("outbox.rows.published").count()).isEqualTo(120.0);
        assertThat(meters.counter("outbox.batches.failed").count()).isZero();

    }

    // ---- two instances -----------------------------------------------------------------------------

    @Test
    void publishBatch_anotherInstanceHoldsTheLock_doesNothingUntilItIsReleased() throws Exception {

        String topic = createTopic(3);
        for (int i = 0; i < 3; i++) {
            insertEvent(topic, uuid(), null);
        }

        // The order of events, in short:
        //   1. Instance A (a second thread) takes the advisory lock and says so (lockHeld).
        //   2. The test thread, acting as Instance B, waits for that, then runs a tick and sees IDLE.
        //   3. The test thread tells A to let go (release); A's transaction ends and the lock is freed.
        //   4. B's next tick publishes.
        // A latch is a one-shot gate: await() blocks until countDown() has been called as many times as the
        // latch was created with (here 1). await(timeout) returns true if the gate opened and false if the
        // timeout passed first - the timeouts below are only safety nets so a broken test fails instead of hanging.

        // "Instance A": a transaction on its own thread that takes the relay's advisory lock and sits on it.
        // "Don't let the test continue until Instance A has definitely acquired the advisory lock."
        // "A" opens this gate (countDown) right after it got the lock; the test thread waits on it.
        CountDownLatch lockHeld = new CountDownLatch(1);

        // "Instance A should hold the lock until I explicitly tell it to release it."
        // The test thread opens this gate (countDown) when it is done checking; A waits on it while holding the lock.
        CountDownLatch release = new CountDownLatch(1);

        // try-with-resources: ExecutorService is AutoCloseable since Java 19, and close() shuts it down and waits
        // for its thread even if an assertion below fails (the old explicit shutdown() was skipped when
        // holder.get() threw).
        //
        // "Give me an executor that has one worker thread.", Simulates the instance A holding the lock.
        try (ExecutorService otherInstance = Executors.newSingleThreadExecutor()) {

            // Instance A, running on the executor's thread: one transaction that takes the lock and holds it.
            // submit() returns at once; "holder" is a handle to wait for A's thread to finish (and to see its failure).
            Future<?> holder = otherInstance.submit(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status -> {

                // The relay's own lock call; true means A really holds the advisory lock now.
                assertThat(outboxRepository.advisoryLockAcquired(RELAY_LOCK_KEY)).isTrue();

                // Instance A says I acquired the lock: opens the lockHeld gate for the test thread.
                lockHeld.countDown();

                try {
                    // A holds the lock until the test thread calls release.countDown().
                    // The 30 s is A's patience: the most it will hold the lock if the test never releases it. If that
                    // happens, fail here (it surfaces through holder.get() below) instead of silently giving up
                    // the lock after the timeout.
                    assertThat(release.await(30, TimeUnit.SECONDS)).isTrue();
                }
                catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));

            try {
                // Test thread waits on lockHeld count to reach 0. Instance A counts it down above.
                // The 10 s is the most we wait for A to get the lock; false (timeout) means A never got it, and
                // the test fails here instead of testing nothing.
                assertThat(lockHeld.await(10, TimeUnit.SECONDS)).isTrue();

                // "Instance B (test thread)" must not wait for the lock and must not send anything.
                BatchResult whileLocked = publisher.publishBatch();

                assertThat(whileLocked.outcome()).isEqualTo(BatchResult.Outcome.IDLE);
                assertThat(pendingSeqs()).hasSize(3); // "A" acquired the lock but did nothing.
                assertThat(consume(topic, 1, Duration.ofSeconds(2))).isEmpty(); // nothing was sent to kafka.
            }
            finally {

                // Open the release gate: A wakes up, its transaction ends and the advisory lock is freed.
                // In finally so A is always let go, even when an assertion above failed.
                release.countDown();

                // Wait for A's thread to finish (10 s at most). If A failed inside its transaction, its exception
                // is rethrown here as an ExecutionException, so a failure in the other thread fails the test.
                holder.get(10, TimeUnit.SECONDS);
            }
        }

        // The lock is released with A's transaction; B's next tick takes over.
        BatchResult afterRelease = publisher.publishBatch();

        assertThat(afterRelease.outcome()).isEqualTo(BatchResult.Outcome.PUBLISHED);
        assertThat(afterRelease.published()).isEqualTo(3);

    }

    // ---- Kafka unavailable -------------------------------------------------------------------------

    @Test
    void publishBatch_brokerUnreachableAfterWarmUp_marksNothingKeepsTheRealErrorAndRecoversInOrder(CapturedOutput output) {

        String topic = createTopic(3);
        // Warm up: the producer now has the topic's metadata cached, like a long-running relay does.
        insertEvent(topic, uuid(), null);
        assertThat(publisher.publishBatch().outcome()).isEqualTo(BatchResult.Outcome.PUBLISHED);

        // Two users, three events each, written while the broker is gone.
        String userA = uuid();
        String userB = uuid();
        List<Seeded> pending = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            pending.add(insertEvent(topic, i % 2 == 0 ? userA : userB, null));
        }

        pauseBroker();
        try {
            long start = System.nanoTime();
            BatchResult result = publisher.publishBatch();
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(result.outcome()).isEqualTo(BatchResult.Outcome.FAILED);
            assertThat(result.published()).isZero();

            // The tick gives up on its own: about send-timeout-ms (5 s), never the producer's old 2 minutes.
            assertThat(tookMs).isBetween(2_000L, 15_000L);

            // Nothing was marked, and only the first unacked row records the failure.
            assertThat(pendingSeqs()).containsExactlyElementsOf(pending.stream().map(Seeded::seq).toList());
            Map<String, Object> first = jdbc.queryForMap("SELECT attempts, last_error FROM outbox_events WHERE seq = ?", pending.getFirst().seq());
            assertThat(first.get("attempts")).isEqualTo(1);

            // Either the producer's own expiry or the relay's deadline got there first - both are timeouts,
            // and neither is spring-kafka's uninformative wrapper.
            assertThat((String) first.get("last_error")).contains("TimeoutException").doesNotContain("Failed to send");

            // Pending rows only (the published warm-up row is excluded): the failed tick charged one row, the
            // first unacked one; the rows behind it were only handed to the producer (the paused broker never
            // got them) and keep attempts = 0.
            assertThat(jdbc.queryForObject("SELECT sum(attempts) FROM outbox_events WHERE published_at IS NULL", Long.class)).isEqualTo(1L);
        }
        finally {
            unpauseBroker();
        }

        // The broker is back: the same rows are sent, and the backlog drains. The producer may need a moment
        // to reconnect, so failed ticks in between are fine.
        await().atMost(Duration.ofSeconds(60))
                .pollInterval(Duration.ofMillis(500))
                .untilAsserted(() -> {
                    publisher.publishBatch();
                    assertThat(pendingSeqs()).isEmpty();
                });

        // Spring's default producer listener would have logged each failed record with its payload (the
        // user's email and display name). RelayKafkaConfig silences it; the payload must not be in any log.
        assertThat(output.getAll()).doesNotContain("payload='").doesNotContain("\"eventId\"");

        // Duplicates are allowed (the outage's abandoned sends may still arrive), losses and reordering of
        // the final state are not: every event is there, and for each user the LAST copy of each event
        // comes in seq order, so the latest state is the last one in the log.
        List<ConsumerRecord<String, String>> records = consume(topic, 7, Duration.ofSeconds(30));

        List<String> expectedIds = pending.stream().map(s -> s.eventId().toString()).toList();
        assertThat(records).extracting(r -> header(r, EventHeaders.EVENT_ID)).containsAll(expectedIds);

        for (String user : List.of(userA, userB)) {

            List<String> userIds = pending.stream().filter(s -> s.aggregateId().equals(user)).map(s -> s.eventId().toString()).toList();

            List<String> lastCopyOrder = records.stream().filter(r -> r.key().equals(user))
                    .map(r -> header(r, EventHeaders.EVENT_ID)).toList();

            // keep each id's last position, then compare the order of those positions
            List<String> byLastOccurrence = userIds.stream()
                    .sorted(Comparator.comparingInt(lastCopyOrder::lastIndexOf)).toList();
            assertThat(byLastOccurrence).containsExactlyElementsOf(userIds);
        }

    }

    @Test
    void publishBatch_producerColdAndBrokerUnreachable_failsAfterOneMetadataWaitNotOnePerRow() {

        // A producer that has never reached a broker (service started while Kafka was down) has no topic
        // metadata, and send() blocks max.block.ms waiting for it and then throws. The publisher must stop at
        // that first row: waiting once per row would hold the lock and a DB connection for rows x max.block.ms.
        Map<String, Object> props = new HashMap<>();
        props.put("bootstrap.servers", "localhost:1"); // nothing listens here
        props.put("key.serializer", StringSerializer.class);
        props.put("value.serializer", StringSerializer.class);
        props.put("max.block.ms", "1000");
        DefaultKafkaProducerFactory<String, String> coldFactory = new DefaultKafkaProducerFactory<>(props);
        try {
            OutboxBatchPublisher coldPublisher = new OutboxBatchPublisher(outboxRepository, new KafkaTemplate<>(coldFactory), 50, 5_000);
            List<Seeded> rows = new ArrayList<>();
            for (int i = 0; i < 20; i++) {
                rows.add(insertEvent("any.topic", uuid(), null));
            }

            long start = System.nanoTime();
            BatchResult result = new TransactionTemplate(transactionManager).execute(status -> coldPublisher.publishBatch());
            long tookMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start);

            assertThat(result.outcome()).isEqualTo(BatchResult.Outcome.FAILED);
            assertThat(tookMs).as("one 1 s metadata wait, not 20 of them").isLessThan(5_000L);
            assertThat(pendingSeqs()).hasSize(20);
            assertThat(jdbc.queryForObject("SELECT last_error FROM outbox_events WHERE seq = ?", String.class, rows.getFirst().seq()))
                    .contains("not present in metadata");
            assertThat(jdbc.queryForObject("SELECT sum(attempts) FROM outbox_events", Long.class)).isEqualTo(1L);
        }
        finally {
            coldFactory.destroy();
        }

    }

    @Test
    void relay_brokerUnreachable_backsOffBetweenFailedTicksAndRecoversOnceKafkaIsBack() {

        String topic = createTopic(3);
        insertEvent(topic, uuid(), null);
        publisher.publishBatch(); // warm the producer
        Seeded stuck = insertEvent(topic, uuid(), null);

        // The relay's clock is ours, so "wait 4 s" is an assignment and not a sleep. Only the real Kafka
        // timeouts (the failed ticks themselves) take wall-clock time.
        // Initial value is 0 for clock.
        AtomicLong nowNanos = new AtomicLong();
        OutboxRelay relay = new OutboxRelay(publisher, new SimpleMeterRegistry(), 10, 4_000, 30_000, nowNanos::get);

        pauseBroker();
        try {
            relay.relay(); // a real attempt because initially backoffUntilNanos = nowNanos: waits out the send deadline, publishes nothing
            assertThat(relay.consecutiveFailedTicks()).isEqualTo(1);
            assertThat(attemptsOf(stuck)).isEqualTo(1);

            // Backing off: the tick returns at once and touches neither the row, nor the lock, nor Kafka.
            long start = System.nanoTime();
            relay.relay();
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - start)).isLessThan(500L); // Check that returned immediately.
            assertThat(attemptsOf(stuck)).isEqualTo(1); // No publishBatch() call, attempt is not bumped.

            nowNanos.addAndGet(TimeUnit.SECONDS.toNanos(4)); // the first delay of 4 secs has passed. Simulate clock.
            relay.relay(); // a second real attempt, still failing: the delay doubles
            assertThat(relay.consecutiveFailedTicks()).isEqualTo(2);
            assertThat(attemptsOf(stuck)).isEqualTo(2);

            relay.relay(); // 8 s delay now: skipped
            assertThat(attemptsOf(stuck)).isEqualTo(2);
        }
        finally {
            unpauseBroker();
        }

        // Kafka is back. Each poll moves the clock past any delay, so every poll is a real attempt; the
        // producer may need a moment to reconnect, so a few failed ticks before the success are fine.
        // Awaitility because the recovery time is not predictable (reconnect, metadata refetch, request
        // timeouts): it retries the tick until the backlog is drained instead of a fixed sleep that is either
        // too short (flaky) or too long (slow), and still fails the test after the limit.
        await()
                // "Don't keep trying for more than 60 real seconds."
                .atMost(Duration.ofSeconds(60))

                // "Between attempts, wait approximately one second."
                .pollInterval(Duration.ofSeconds(1))

                // "Run the condition on the same thread as the test."
                .pollInSameThread()

                // Awaitility repeatedly runs the following Runnable lambda it until all assertions inside it pass.
                .untilAsserted(() -> {
                    nowNanos.addAndGet(TimeUnit.SECONDS.toNanos(30)); // Bypass any backoff wait.
                    relay.relay();
                    assertThat(pendingSeqs()).isEmpty(); // Backlog is drained.
                });

        assertThat(relay.consecutiveFailedTicks()).isZero();

    }

    // ---- test helpers ------------------------------------------------------------------------------

    private record Seeded(long seq, UUID eventId, String aggregateId) {
    }

    private static String uuid() {

        return UUID.randomUUID().toString();

    }

    // Same partition count as infra/kafka/kafka-topics.sh; the Testcontainers broker would auto-create the
    // topic with one partition otherwise, and the per-key ordering checks need several.
    private String createTopic(int partitions) {

        String topic = "relay-it-" + UUID.randomUUID();
        try (Admin admin = Admin.create(Map.<String, Object>of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, partitions, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
        catch (Exception e) {
            throw new IllegalStateException("could not create topic " + topic, e);
        }
        return topic;

    }

    // Plain SQL, like OutboxEventSchemaIT: the relay reads rows, it does not matter how they got there.
    // The payload carries the event id so a consumed record can be matched to its row.
    private Seeded insertEvent(String topic, String aggregateId, UUID correlationId) {

        UUID eventId = UUID.randomUUID();
        Long seq = jdbc.queryForObject("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, aggregate_version, event_type, topic,
                                           payload, correlation_id, occurred_at)
                VALUES (CAST(? AS uuid), ?, ?, 0, 'UserUpdated', ?, CAST(? AS jsonb), CAST(? AS uuid), CURRENT_TIMESTAMP)
                RETURNING seq
                """,
                Long.class,
                eventId.toString(), UserEvents.AGGREGATE_TYPE, aggregateId, topic,
                "{\"eventId\": \"" + eventId + "\"}", correlationId == null ? null : correlationId.toString());

        return new Seeded(seq, eventId, aggregateId);

    }

    private List<Long> pendingSeqs() {

        return jdbc.queryForList("SELECT seq FROM outbox_events WHERE published_at IS NULL ORDER BY seq", Long.class);

    }

    private int attemptsOf(Seeded row) {

        return Objects.requireNonNull(jdbc.queryForObject("SELECT attempts FROM outbox_events WHERE seq = ?", Integer.class, row.seq()));

    }

    private static ConsumerRecord<String, String> recordOf(List<ConsumerRecord<String, String>> records, Seeded row) {

        return records.stream()
                .filter(r -> header(r, EventHeaders.EVENT_ID).equals(row.eventId().toString()))
                .findFirst()
                .orElseThrow(() -> new AssertionError("event " + row.eventId() + " is not in the topic"));

    }

    private static String header(ConsumerRecord<String, String> record, String name) {

        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);

    }

    // Reads the topic from the beginning with a throwaway consumer group, until records for at least
    // minDistinctEvents different events have arrived (or the timeout passes), then keeps listening for one
    // more second so duplicates show up. Counting distinct events, not records, because after an outage a
    // few records legitimately arrive twice and must not make the read stop before the others are in.
    private List<ConsumerRecord<String, String>> consume(String topic, int minDistinctEvents, Duration timeout) {

        Properties props = new Properties();
        props.put("bootstrap.servers", kafka.getBootstrapServers());
        props.put("group.id", "relay-it-" + UUID.randomUUID());

        // with no saved position, start from the first record.
        props.put("auto.offset.reset", "earliest");
        props.put("enable.auto.commit", "false");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());

        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        Set<String> eventIds = new HashSet<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + timeout.toNanos();
            while (eventIds.size() < minDistinctEvents && System.nanoTime() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(300))) {
                    records.add(record);
                    eventIds.add(header(record, EventHeaders.EVENT_ID));
                }
            }
            if (!records.isEmpty()) {
                consumer.poll(Duration.ofSeconds(1)).forEach(records::add);
            }
        }
        return records;

    }

    // "Broker down" without losing its port: a paused container keeps its mapped port but answers nothing,
    // like a machine that dropped off the network. Stopping it would give the restarted broker a new port.
    private void pauseBroker() {

        // docker-java commands are AutoCloseable, so try-with-resources is the form expected. For these
        // two commands close() releases nothing; it only keeps the resource-leak inspection quiet.
        try (PauseContainerCmd pause = kafka.getDockerClient().pauseContainerCmd(kafka.getContainerId())) {
            pause.exec();
        }

    }

    private void unpauseBroker() {

        try (UnpauseContainerCmd unpause = kafka.getDockerClient().unpauseContainerCmd(kafka.getContainerId())) {
            unpause.exec();
        }

    }
}
