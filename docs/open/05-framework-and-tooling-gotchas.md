# Framework and tooling gotchas

Verified in this repository. Check here before assuming a library behaves the way an older version did.

## Spring Boot 4 and Spring 7

- Starter renames: `spring-boot-starter-security-oauth2-resource-server`; `spring-boot-starter-aspectj` (Boot 4 no longer manages `spring-boot-starter-aop`); `@DataJpaTest` lives in `spring-boot-starter-data-jpa-test`; `@WebMvcTest` in `spring-boot-starter-webmvc-test`.
- `ProblemDetail.getType()` may be null (Spring 6 defaulted to `about:blank`). Trailing-slash URLs no longer match. `@Min`/`@Max` on a `@RequestParam` validates natively (`HandlerMethodValidationException`). Standalone MockMvc needs `AuthenticationPrincipalArgumentResolver` for `@AuthenticationPrincipal`. `PageImpl` silently corrects inconsistent totals; an unpaged page reports size = content size.
- Spring Boot 4.1.1 ships structured logging (`logging.structured.format.console=ecs|gelf|logstash`, MDC included). A flat dotted key under `logging.level` works in YAML.
- `micrometer-core` (`Gauge`, `MeterBinder`, `MeterRegistry`) is not declared in user-service's pom: it arrives through `spring-boot-starter-actuator`.
- `resilience4j-spring-boot3:2.4.0` compiles against Boot 4.1.1, but user-service does not use its Spring integration: `resilience4j-circuitbreaker` and `resilience4j-retry` are used programmatically (versions from the root BOM).
- `wiremock-standalone` is a test dependency (version is a root property): its shaded Jetty and Jackson cannot clash with Boot's Tomcat and Jackson 3.
- Spring Kafka's default dead-letter topic is `<topic>-dlt`; our topics use `<topic>.dlt`, so consumers need a custom destination resolver.
- With Spring, a `@SpringBootTest` that keeps a fast scheduler must not stay in the context cache: a cached context keeps its jobs ticking against the shared database. Such tests use `@DirtiesContext(AFTER_CLASS)`.

## Jackson 3

Packages are `tools.jackson.databind.*` (annotations are still `com.fasterxml.jackson.annotation`). Unknown properties are ignored by default (`@JsonIgnoreProperties(ignoreUnknown = false)` does not change that). `writeValueAsString` throws unchecked exceptions.

## Testcontainers 2.x

Modules are `testcontainers-postgresql`, `testcontainers-junit-jupiter`, …; `org.testcontainers.postgresql.PostgreSQLContainer`; `@ServiceConnection` overrides `spring.datasource.*`.

## Hibernate and JPA

- A native `@Modifying` query bypasses the persistence context: an entity loaded earlier in the same transaction stays stale. Use `clearAutomatically = true` (detaches everything), then re-read.
- Hibernate auto-flushes pending changes before native queries; `JdbcTemplate` does not (flush first).
- `saveAndFlush` of an unchanged entity, or a setter with the same value, issues no `UPDATE` and no version bump.
- Hibernate 7.4: a versioned entity with a column the database generates on update (`@Generated(event = UPDATE)`, here `users.updated_at`) is updated with `RETURNING`, and an `UPDATE` that matches no row (another transaction changed the row since it was read) does not raise an optimistic-locking failure: it fails with `JpaSystemException: The database returned no natively generated values`, which is a 500. The stale-copy test only reaches the other path (`saveAndFlush` of a detached copy compares versions in `merge`). So a read-modify-write of such an entity reads the row `FOR UPDATE` (`@Lock(PESSIMISTIC_WRITE)`, as `UserRepository.findByIdForUpdate` does for the profile update): the writers queue on the row lock instead of colliding. The service's other writes are native compare-and-set `UPDATE`s and do not depend on this.
- In `@DataJpaTest` (one transaction per test): flush before JDBC reads, clear before entity re-reads.
- Lombok: `@Value` on a `final` field plus `@RequiredArgsConstructor` makes Spring look for a bean of type `int`.

## PostgreSQL

- Row locks are held until commit or rollback, and a blocked `UPDATE` re-evaluates its `WHERE` against the committed row (READ COMMITTED): the basis of every compare-and-set here.
- `pg_try_advisory_xact_lock(key)` is non-blocking, released at transaction end, scoped per database and not tied to any table; callable as a Spring Data native query returning `boolean`.
- `CURRENT_TIMESTAMP` is the transaction's start time: a test that inserts and then updates in one transaction must seed old timestamps.
- Unquoted identifiers fold to lowercase.
- pgjdbc 42.7 and `JdbcTemplate` cannot bind a `java.time.Instant` parameter (both handle `OffsetDateTime`, the JDBC 4.2 type for `timestamptz`); reading one back as `Instant.class` works. Tests bind through `testsupport/data/JdbcParameters.bindable(...)`.

## Kafka

`apache/kafka:4.3.1`: the CLI is at `/opt/kafka/bin/`, the entrypoint ends in `exec "$@"` (override `command` only), and it runs as `appuser`. Use dots only in topic names (Kafka warns that `.` and `_` collide in metric names).

- Spring Kafka: `@KafkaListener(id = ...)` is also the consumer group id unless `idIsGroup = false`. A `DefaultErrorHandler` classifies the whole cause chain, so a `PoisonEventException` wrapped in `ListenerExecutionFailedException` is still non-retryable. `setBackOffFunction` returning `null` falls back to the handler's default back-off, and an `ExponentialBackOff` has unlimited attempts and elapsed time until configured otherwise. A record published by `DeadLetterPublishingRecoverer` names Spring's wrapper in `kafka_dlt-exception-fqcn`; the exception the listener threw is in `kafka_dlt-exception-cause-fqcn`. A record whose value is a `String` can be republished with the same `KafkaTemplate<String, String>`; one that failed in a deserializer could not.
- Spring Boot's `KafkaTemplate` and `ProducerFactory` exist only while the context has none of its own (`@ConditionalOnMissingBean`): a service with two producers declares all of them itself, as `KafkaProducerConfig` does (`relayKafkaTemplate`, `deadLetterKafkaTemplate` and a factory each), and a user picks one with `@Qualifier`; declaring only a second one would make Boot drop its own, and every user of a plain `KafkaTemplate` would silently get the second. Both factories are built the way Boot builds its own (`KafkaProperties.buildProducerProperties()`, the connection details, the `DefaultKafkaProducerFactoryCustomizer` beans) by one private method, each with the client id of its own from `app.kafka.*`: `spring.kafka.producer.*` holds only what the producers share, and a `client-id` there would be overwritten.
- A `KafkaTemplate` built by hand defaults to `LoggingProducerListener`, which logs the payload of every failed send; give it the silent `ProducerListener` bean (Boot does that only for its own template).
- In Boot 4 the bootstrap servers of a Testcontainers `@ServiceConnection` come from `KafkaConnectionDetails`, which Boot's auto-configuration applies on top of `KafkaProperties.buildProducerProperties()`: producer properties built from `KafkaProperties` alone would point a test at the yaml's `localhost:9094`.
- The consumer has `allow.auto.create.topics=false`, so a context that starts a listener needs its topics first: `KafkaTestContainerConfig` declares them as `NewTopics`, which `KafkaAdmin` creates while the context refreshes, before any listener container starts.
- A `src/test/resources/application.yaml` would hide the main one completely (the classpath yields the first file of that name; nothing merges). Test defaults live in `src/test/resources/application.properties`, which is loaded as well and wins for the keys it sets.
- Tests: `@MockitoSpyBean` on a `@Transactional` service keeps the transaction (the spy sits inside the proxy). A bare `ApplicationContextRunner` has no Boot conversion service, so an empty property becomes a list with one empty string instead of an empty list (give it `ApplicationConversionService.getSharedInstance()`). A static `await` method in a test class shadows Awaitility's static import of the same name.

## Keycloak

- Import: `${NAME}` in the realm file is replaced by the environment variable `NAME`, and a placeholder with no such variable is left as it is (that is why the theme placeholders like `${username}` survive, and why an unset secret variable becomes the literal placeholder text).
- The user-profile configuration is imported from the realm's `components` as one JSON document escaped into a string.
- Keycloak marks its cookies `Secure` even on `http://localhost`, so a Java test that plays a browser has to handle cookies by hand (`KeycloakBrowser`).
- A password grant for a user that does not exist is answered 400 `invalid_grant`, not 401.
- More: `infra/keycloak/README.md`.

## Working on this machine

- Windows with a Turkish default locale: `String.toUpperCase()` turns `i` into a dotted `İ`. Use `Locale.ROOT` in tests that build addresses or compare case.
- Git Bash needs `MSYS_NO_PATHCONV=1` before `docker exec/run` with absolute container paths.
- The Maven wrapper is `./mvnw` in a POSIX shell and `.\mvnw.cmd` in PowerShell.
- After moving or renaming classes, run `clean` before `verify`: stale classes in `target/` break Surefire.
- Working-tree files are a mix of CRLF and LF; keep a file's existing line endings when editing it.
- The console code page is cp1254; set `PYTHONIOENCODING=utf-8` when a script prints non-ASCII text.
