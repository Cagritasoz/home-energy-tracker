package com.cagritasoz.user_service.testsupport.containers;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaAdmin;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestContainerConfig {

    public static final String KAFKA_IMAGE = "apache/kafka:4.3.1";

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {

        return new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE));

    }

    // The topics the application consumes, created the way infra/kafka/kafka-topics.sh creates them (same partition
    // counts). The application's consumer never creates a topic (allow.auto.create.topics=false, as against the real
    // broker), so a context that starts the listener needs them before it starts: Spring's KafkaAdmin creates every
    // NewTopic bean while the context is being refreshed, before any listener container starts.
    @Bean
    KafkaAdmin.NewTopics consumedTopics() {

        return new KafkaAdmin.NewTopics(
                TopicBuilder.name("device.events.v1").partitions(3).replicas(1).build(),
                TopicBuilder.name("device.events.v1.dlt").partitions(1).replicas(1).build());

    }
}
