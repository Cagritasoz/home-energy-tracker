package com.cagritasoz.user_service.relay;

import com.cagritasoz.contracts.EventHeaders;
import com.cagritasoz.contracts.user.UserEvents;
import com.cagritasoz.user_service.entity.OutboxEvent;
import com.cagritasoz.user_service.repository.OutboxRepository;
import com.cagritasoz.user_service.testsupport.KafkaTestContainerConfig;
import com.cagritasoz.user_service.testsupport.PostgresTestContainerConfig;
import io.micrometer.core.instrument.MeterRegistry;
import org.apache.kafka.clients.admin.Admin;
import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.testcontainers.kafka.KafkaContainer;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// The relay as the application runs it: real beans, real properties, and Spring's own scheduler firing the
// ticks - nothing here calls relay() or publishBatch() by hand (RelayIT does, on purpose, to control timing).
//
// What this class can catch that the other relay tests cannot:
//  - a property that does not bind (app.outbox.relay.backoff.*, app.outbox.purge.*) or an invalid cron
//    string: the context would not start, so every test here would fail;
//  - the two-constructor OutboxRelay not being built by Spring the way the tests build it by hand;
//  - the proxy path: @Scheduled OutboxRelay -> @Transactional OutboxBatchPublisher (a method called on
//    "this" instead of the proxy would silently skip the transaction - it happened once);
//  - the meters really being registered in the application's MeterRegistry.
//
// The interval is 200 ms (the application's is 2 s) so the test does not wait seconds for a tick.
@SpringBootTest(
        classes = RelaySchedulingIT.SchedulingTestApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = "app.outbox.relay.interval-ms=200")
@Import({PostgresTestContainerConfig.class, KafkaTestContainerConfig.class})
class RelaySchedulingIT {

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    @EnableScheduling
    @EntityScan(basePackageClasses = OutboxEvent.class)
    @EnableJpaRepositories(basePackageClasses = OutboxRepository.class)
    @Import({OutboxBatchPublisher.class, OutboxRelay.class, OutboxMetrics.class, OutboxPurgeJob.class, RelayKafkaConfig.class})
    static class SchedulingTestApplication {
    }

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private KafkaContainer kafka;

    @Autowired
    private MeterRegistry meterRegistry;

    @Autowired
    private OutboxRelay outboxRelay;

    @Autowired
    private OutboxPurgeJob outboxPurgeJob;

    @BeforeEach
    void cleanOutbox() {

        jdbc.update("DELETE FROM outbox_events");

    }

    @Test
    void context_realRelayAndPurgeBeans_areCreatedFromTheApplicationProperties() {

        // Reaching this line is the test: the context only starts if every @Value placeholder resolved, the
        // @Autowired OutboxRelay constructor was chosen, and the purge job's cron expression parsed.
        assertThat(outboxRelay).isNotNull();
        assertThat(outboxPurgeJob).isNotNull();
        assertThat(outboxRelay.consecutiveFailedTicks()).isZero();

    }

    @Test
    void scheduler_pendingRow_isPublishedWithoutAnyManualTick() {

        String topic = createTopic();
        UUID eventId = insertEvent(topic, "user-1");

        awaitPublished(eventId);

        List<ConsumerRecord<String, String>> records = consume(topic, 1);
        assertThat(records).hasSize(1);
        assertThat(records.getFirst().key()).isEqualTo("user-1");
        assertThat(new String(records.getFirst().headers().lastHeader(EventHeaders.EVENT_ID).value(), StandardCharsets.UTF_8))
                .isEqualTo(eventId.toString());

    }

    @Test
    void meters_areRegisteredInTheApplicationRegistryAndMoveWithTheRelay() {

        double publishedBefore = meterRegistry.get("outbox.rows.published").counter().count();
        String topic = createTopic();
        UUID eventId = insertEvent(topic, "user-2");

        awaitPublished(eventId);

        // The counter is updated after the batch returns, a moment after the row is marked: wait for it too.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(meterRegistry.get("outbox.rows.published").counter().count()).isGreaterThan(publishedBefore));
        assertThat(meterRegistry.get("outbox.pending.rows").gauge().value()).isZero();
        assertThat(meterRegistry.get("outbox.parked.rows").gauge().value()).isZero();
        assertThat(meterRegistry.get("outbox.oldest.pending.age.seconds").gauge().value()).isZero();
        assertThat(meterRegistry.get("outbox.relay.consecutive.failed.ticks").gauge().value()).isZero();

    }

    // ---- helpers (small copies of RelayIT's) -------------------------------------------------------

    private void awaitPublished(UUID eventId) {

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertThat(jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_events WHERE id = CAST(? AS uuid)",
                        Boolean.class, eventId.toString())).isTrue());

    }

    private String createTopic() {

        String topic = "relay-scheduling-it-" + UUID.randomUUID();
        try (Admin admin = Admin.create(Map.<String, Object>of("bootstrap.servers", kafka.getBootstrapServers()))) {
            admin.createTopics(List.of(new NewTopic(topic, 3, (short) 1))).all().get(30, TimeUnit.SECONDS);
        }
        catch (Exception e) {
            throw new IllegalStateException("could not create topic " + topic, e);
        }
        return topic;

    }

    private UUID insertEvent(String topic, String aggregateId) {

        UUID eventId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO outbox_events (id, aggregate_type, aggregate_id, aggregate_version, event_type, topic, payload, occurred_at)
                VALUES (CAST(? AS uuid), ?, ?, 0, 'UserUpdated', ?, CAST(? AS jsonb), CURRENT_TIMESTAMP)
                """,
                eventId.toString(), UserEvents.AGGREGATE_TYPE, aggregateId, topic, "{\"eventId\": \"" + eventId + "\"}");
        return eventId;

    }

    private List<ConsumerRecord<String, String>> consume(String topic, int minRecords) {

        Properties props = new Properties();
        props.put("bootstrap.servers", kafka.getBootstrapServers());
        props.put("group.id", "relay-scheduling-it-" + UUID.randomUUID());
        props.put("auto.offset.reset", "earliest");
        props.put("enable.auto.commit", "false");
        props.put("key.deserializer", StringDeserializer.class.getName());
        props.put("value.deserializer", StringDeserializer.class.getName());

        List<ConsumerRecord<String, String>> records = new ArrayList<>();
        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.nanoTime() + Duration.ofSeconds(20).toNanos();
            while (records.size() < minRecords && System.nanoTime() < deadline) {
                consumer.poll(Duration.ofMillis(300)).forEach(records::add);
            }
            // keep listening a moment so a duplicate would show up in the size check
            consumer.poll(Duration.ofSeconds(1)).forEach(records::add);
        }
        return records;

    }
}
