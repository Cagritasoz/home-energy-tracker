package com.cagritasoz.user_service.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.utility.DockerImageName;

// Test-only configuration that gives Spring a real, throwaway Kafka broker (KRaft, one node), the same
// way PostgresTestContainerConfig does for Postgres. @ServiceConnection turns the container's random
// host port into spring.kafka.bootstrap-servers, overriding the value in application.yaml.
//
// Same image as infra/docker-compose.yml. Unlike the compose setup, this broker auto-creates topics;
// tests that care about partitions create their topic first (see RelayIT).
@TestConfiguration(proxyBeanMethods = false)
public class KafkaTestContainerConfig {

    public static final String KAFKA_IMAGE = "apache/kafka:4.3.1";

    @Bean
    @ServiceConnection
    KafkaContainer kafka() {

        return new KafkaContainer(DockerImageName.parse(KAFKA_IMAGE));

    }
}
