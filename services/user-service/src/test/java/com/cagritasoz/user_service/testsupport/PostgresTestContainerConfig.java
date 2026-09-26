package com.cagritasoz.user_service.testsupport;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;

// Test-only configuration that gives Spring a real, throwaway Postgres. Any Layer 2 test class opts in
// with @Import(PostgresTestContainerConfig.class).
//
// How it works: Spring creates the container bean, starts it (Docker pulls the image the first time),
// and because of @ServiceConnection it reads the container's random host port, user and password and
// uses them as the DataSource - overriding spring.datasource.* in application.properties. So no
// USER_DB_PASSWORD env var and no docker compose are needed to run these tests.
//
// Flyway then migrates the empty database exactly like production, and Hibernate's
// ddl-auto=validate checks the entities against it - so just starting the context already proves the
// migrations and the entity mappings agree.
@TestConfiguration(proxyBeanMethods = false)
public class PostgresTestContainerConfig {

    // Same major version as infra/docker-compose.yml, so tests run against what production runs.
    private static final String POSTGRES_IMAGE = "postgres:16-alpine";

    // The container lives in Spring's context, and Spring caches contexts between test classes that
    // share the same configuration - so one container is started for the whole test run, not one per
    // class. Testcontainers' Ryuk helper container removes it when the JVM exits, even after a crash.
    @Bean
    @ServiceConnection
    PostgreSQLContainer postgres() {

        return new PostgreSQLContainer(POSTGRES_IMAGE);

    }
}
