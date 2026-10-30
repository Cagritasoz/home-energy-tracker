package com.cagritasoz.user_service.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.kafka.listener.ContainerProperties;

import java.util.Map;
import java.util.function.BiConsumer;

import static org.assertj.core.api.Assertions.assertThat;

// What the application's own application.yaml (with the test defaults on top) says about Kafka, read the way Spring Boot
// reads it. The consumer's settings are written down in the yaml on purpose, so that a client upgrade cannot change how
// the group behaves; this keeps them from being edited by accident, and the two producers from sharing a client id.
class KafkaConfigurationTest {

    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(KafkaProperties.class)
    static class Properties {
    }

    private void withTheApplicationYaml(BiConsumer<KafkaProperties, Environment> check) {

        new ApplicationContextRunner()
                .withInitializer(new ConfigDataApplicationContextInitializer())
                .withUserConfiguration(Properties.class)
                .run(context -> check.accept(context.getBean(KafkaProperties.class), context.getEnvironment()));

    }

    private static String value(Map<String, Object> properties, String key) {

        assertThat(properties).as(key + " is set").containsKey(key);

        return String.valueOf(properties.get(key));

    }

    @Test
    void theConsumerSettingsAreWrittenDown() {

        withTheApplicationYaml((kafka, environment) -> {

            Map<String, Object> consumer = kafka.buildConsumerProperties();

            assertThat(value(consumer, ConsumerConfig.GROUP_ID_CONFIG)).isEqualTo("user-service");
            assertThat(value(consumer, ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG)).isEqualTo("false");
            assertThat(value(consumer, ConsumerConfig.AUTO_OFFSET_RESET_CONFIG)).isEqualTo("earliest");
            assertThat(value(consumer, ConsumerConfig.ISOLATION_LEVEL_CONFIG)).isEqualTo("read_committed");
            assertThat(value(consumer, ConsumerConfig.MAX_POLL_RECORDS_CONFIG)).isEqualTo("100");
            assertThat(value(consumer, ConsumerConfig.FETCH_MIN_BYTES_CONFIG)).isEqualTo("1");
            assertThat(value(consumer, ConsumerConfig.FETCH_MAX_WAIT_MS_CONFIG)).isEqualTo("500");
            assertThat(value(consumer, ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG)).isEqualTo("3000");
            assertThat(value(consumer, ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG)).isEqualTo("45000");
            assertThat(value(consumer, ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG)).isEqualTo("300000");
            assertThat(value(consumer, ConsumerConfig.GROUP_PROTOCOL_CONFIG)).isEqualTo("classic");
            assertThat(value(consumer, ConsumerConfig.PARTITION_ASSIGNMENT_STRATEGY_CONFIG)).isEqualTo("org.apache.kafka.clients.consumer.CooperativeStickyAssignor");
            assertThat(value(consumer, ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG)).as("a consumer never creates a topic").isEqualTo("false");

            assertThat(kafka.getListener().getAckMode()).isEqualTo(ContainerProperties.AckMode.RECORD);
            assertThat(kafka.getListener().getConcurrency()).isEqualTo(1);

        });

    }

    @Test
    void theHeartbeatIsWellInsideTheSessionTimeout_andThePollIntervalCoversTheLongestPause() {

        withTheApplicationYaml((kafka, environment) -> {

            Map<String, Object> consumer = kafka.buildConsumerProperties();

            long heartbeat = Long.parseLong(value(consumer, ConsumerConfig.HEARTBEAT_INTERVAL_MS_CONFIG));
            long session = Long.parseLong(value(consumer, ConsumerConfig.SESSION_TIMEOUT_MS_CONFIG));
            long pollInterval = Long.parseLong(value(consumer, ConsumerConfig.MAX_POLL_INTERVAL_MS_CONFIG));

            assertThat(heartbeat * 3).as("Kafka's rule of thumb: at most a third of the session timeout").isLessThanOrEqualTo(session);
            assertThat(pollInterval).as("at least ten times the longest database pause of the error handler (30 s)").isGreaterThanOrEqualTo(10 * 30_000L);

        });

    }

    @Test
    void theTwoProducersDoNotShareAClientId() {

        withTheApplicationYaml((kafka, environment) -> {

            String relay = environment.getProperty("app.kafka.relay-producer.client-id");
            String deadLetter = environment.getProperty("app.kafka.dead-letter-producer.client-id");

            assertThat(relay).isEqualTo("user-service-outbox-relay");
            assertThat(deadLetter).isEqualTo("user-service-dead-letter").isNotEqualTo(relay);

            // What the producers share carries no client id: each producer adds its own.
            assertThat(kafka.buildProducerProperties()).doesNotContainKey(ProducerConfig.CLIENT_ID_CONFIG);

        });

    }
}
