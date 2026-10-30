# Testing strategy

## Split and style

- A class that needs Docker (Testcontainers) is named `*IT` and runs in Failsafe (`integration-test` phase, after `package`); everything else is `*Test` and runs in Surefire. Failsafe is declared once in the root pom (configured by the Boot parent); Surefire needs no declaration. `./mvnw test` needs no Docker; `./mvnw verify` does.
- Test classes sit in the package of the feature they test (`04-conventions.md`).
- Names are `method_scenario_outcome`. Shared fixtures: `UserFixtures`, `JwtFixtures` (personas Arthur Morgan, Leon Kennedy, John Marston, Jack Carver, Jason Brody). Explicit imports, explanatory comments. Every timestamp is an `Instant`; JDBC parameters go through `testsupport/data/JdbcParameters.bindable(...)`, which converts at the boundary.
- **Verify behaviour empirically.** Run it; mutation-check a test by breaking the code it guards, confirming the test fails, then restoring the file byte for byte. A test that cannot fail proves nothing.
- Use `Locale.ROOT` where case matters (`05-framework-and-tooling-gotchas.md`).

## Layers in user-service

**Unit (Mockito, no Spring)**: the services (`UserProfileServiceTest`, `DeletionSchedulingServiceTest`, `DeletionPromotionServiceTest`, `KeycloakDeletionServiceTest`, `OrphanEmailServiceTest`, `UserProvisioningServiceTest`, `AdminUserServiceTest`), the jobs (`DeletionPromotionJobTest`, `KeycloakDeletionJobTest`), `UpdateUserRequestTest`, `GlobalExceptionHandlerTest`, and the controllers through standalone MockMvc (`@PreAuthorize` and URL rules are covered by the security tests).

**Security** (`security/`, plus `provisioning/` for the interceptor; no Docker except `SecurityFlowIT`). Requests carry real RS256 JWTs signed in the test (`testsupport/jwt/TestTokens`), and the real `SecurityConfig` decoder fetches the keys over HTTP from `testsupport/jwt/LocalJwks` (a JDK `HttpServer` serving a JWKS), so signature, `exp`/`nbf` (60 s skew), issuer, audience, role mapping, URL rules, handlers and CORS run unmocked. `jwt()` is not used: it skips decoding and the role converter. `@SecuritySlice` is the shared `@WebMvcTest` setup.
- `SecurityAuthenticationTest`: 401 cases, the `invalid_token` challenge, forged, unsigned, HS256-confusion and tampered tokens.
- `SecurityAuthorizationTest`: the role matrix (14 token shapes against 6 routes), firewall rejections, headers, stateless, no CSRF.
- `ProvisioningInterceptorWiringTest`, `SecurityCorsTest`, `MethodSecurityTest` (a permit-all chain, so `@PreAuthorize` is the only guard), `JwksKeyRotationTest`, `JwksUnavailableTest`, and unit tests for `AudienceValidator`, `KeycloakRealmRoleConverter`, both problem-detail handlers, `TokenVerificationUnavailableFilter` and `UserProvisioningInterceptor`.
- `SecurityFlowIT` (Postgres): provisioning, an admin never gets a `users` row or an event, a scheduled account is readable and read-only, a `DELETING` one is 410 with a still-valid token, the admin view lists participants, actuator exposure.
- The `security` package, `WebMvcConfig` and `UserProvisioningInterceptor` have 100 % line, branch and method coverage from the Docker-free tests (the `@NonNull` null-argument cases exist only to cover Lombok's generated guards).
- Pinned behaviour worth knowing: an unreachable, failing or garbage key endpoint answers 503 `service-unavailable` (the filter sits before the bearer token filter because Spring Security rethrows `AuthenticationServiceException` instead of calling the entry point; an empty key set is an ordinary 401); the bearer filter rejects an invalid token even on public `/actuator/health`; cached keys keep validating until the decoder refreshes them. A token whose `exp` is before its `iat` is rejected by Spring's `Jwt` constructor, so test tokens move `iat` back when `exp` is set in the past.

**Repository and schema** (`@DataJpaTest` + `replace = NONE` + `@Import(PostgresTestContainerConfig)`: `postgres:16-alpine` through `@ServiceConnection`, shared through Spring's context cache):
- `UserSchemaIT`: primary key, NOT NULLs, defaults, every CHECK (each deletion rule has an accepted and a rejected case built from `columnsFor(status)`, a legal row of that status, so only the rule under test can fire), `uq_users_email`, the deletion indexes, the `updated_at` trigger. `UserDeletionParticipantsSchemaIT` for V13.
- `UserRepositoryIT`: mapping, `@Version`, stale copy → `OptimisticLockingFailureException`, paging order, and every outcome of the native queries (row counts, compare-and-set guards, constraint violations, detached entities after `clearAutomatically`).
- `OutboxEventSchemaIT` (plain SQL: identity order, uniqueness, defaults, NOT NULLs, every CHECK, jsonb payload, V8's checks, the partial index definitions via `pg_indexes`), `ProcessedEventSchemaIT` (V10: primary key per `(event_id, handler)`, `ON CONFLICT DO NOTHING` returns 0 on redelivery), `UserDeletionParticipantRepositoryIT`.
- `MigrationUpgradeIT` (no Spring, its own container): Flyway to V7 in a fresh schema, rows inserted, upgraded to latest; an inconsistent V7 row makes V9 fail while V8 stays applied; a V10 database with `DELETING` and `DELETED` rows is backfilled by V11 and the old columns are gone.

**Outbox and relay**: listed in `docs/design/01-outbox-design.md`, section 15.

**Saga, Keycloak admin client, the finalizer and the confirmation consumer**: listed in `docs/design/03-account-deletion-saga.md`, section 16. The consumer container and `processed_events`: `docs/design/02-processed-events-design.md`, section 8.

**Kafka in tests**: `testsupport/containers/KafkaTestContainerConfig` starts a broker (`@ServiceConnection`), declares the topics the application consumes (`NewTopics`, as `kafka-topics.sh` creates them) and `testsupport/kafka/KafkaTestClient` plays another service: it produces a message by hand and reads a whole topic from the beginning (the dead-letter topic, the events the relay published). A full-context test that starts the confirmation listener sets `app.user.deletion.ack-consumer.enabled=true` and closes its context after the class; every other full-context test gets the listener switched off, and the finalizer parked at a day, by `src/test/resources/application.properties`. Without a broker: `KafkaProducersWiringTest` (Boot's auto-configuration and `KafkaProducerConfig` in an `ApplicationContextRunner`: the two templates and their factories, a client id each (a stale `spring.kafka.producer.client-id` overwritten, a missing one failing the start-up), the connection details and factory customizers on both, the silent listener, each user getting the right template) and `KafkaConfigurationTest` (the pinned consumer settings, the two client ids and no client id in the shared producer settings, read from the real `application.yaml`).

**Keycloak realm** (Docker, ~40 s each; `testsupport/containers/ComposeFile` reads image tags and the listener option from `docker-compose.yml`):
- `RealmFileTest` (no Docker): no `"//"` keys, the embedded user profile equals `user-profile.json`.
- `KeycloakRealmIT`: the real realm file in the real Keycloak image plus Mailpit: realm settings, the profile lock, the service account's exact roles and rights, the application admin's 403 on Keycloak's admin API, duplicate emails (also for disabled users), admin events naming who changed an email, registration (email only, verification mail, then password), forgot-password, and the mails (one "Update password" per change, nothing on a failed login). Also pinned: a verification or reset link opened on another device ends on "Account updated" with a Back to Application link; the frontend's `kc_action=UPDATE_PASSWORD` for a signed-in user skips the login and both buttons return to the application with `kc_action_status`; any realm user can sign in to the Account Console client.
- `KeycloakEventOrderIT` (its own Keycloak with `KC_LOG_LEVEL=INFO,org.keycloak.events:DEBUG`; the log is parsed per user id): the events each scenario fires, in order. The scenario table in `infra/keycloak/README.md` is this test's output.

**Contract**: `UserEventContractTest` (Mockito, plain `JsonMapper`) compares what `OutboxService.record*` saves with the fixture file, and a parameterized test requires a fixture at the current `SCHEMA_VERSION` for every `UserEventType` (the `switch` over the enum stops compiling when a type is added without a version). `testsupport/data/ContractFixtures` loads the files.

## Concurrency tests

Opt out of `@DataJpaTest`'s rolled-back transaction (`@Transactional(propagation = NOT_SUPPORTED)`), give each thread its own transaction (`TransactionTemplate` with `REQUIRES_NEW`), clean up afterwards, and force the overlap deterministically (hold A's transaction open, wait until B is blocked, for example through `pg_stat_activity` with Awaitility, then commit A) instead of relying on timing. Each race test sits next to the feature it races (`DeletionPromotionConcurrencyIT` is the model), not in a repository-level class.

## Coverage

`./mvnw -pl services/user-service -am verify -Pcoverage`: the `coverage` profile in the root pom attaches the JaCoCo agent (unit and integration tests); the report is `services/user-service/target/site/jacoco/index.html`.

## What is missing

- Tests of a consumer that applies state (the version guard and the tombstone): the first one is device-service's handling of user events.
- The saga's remaining concurrency and failure tests (`docs/design/03-account-deletion-saga.md`, section 17).
- End-to-end tests (`e2e/` is a stub).

## CI

`.github/workflows/ci.yml`: one job, `./mvnw --batch-mode --no-transfer-progress verify` on `ubuntu-latest` (Docker is present for Testcontainers): Surefire runs `*Test`, then Failsafe runs `*IT`. `-Dtest='**/*Test,**/*Tests,!**/*ApplicationTests'` excludes the legacy services' `*ApplicationTests` (they need a live Postgres and Keycloak issuer); the include patterns must be listed because `-Dtest` replaces Surefire's defaults (an exclusion alone made Surefire run every `*IT` too). Still to come: a contract stage, container images, end-to-end.
