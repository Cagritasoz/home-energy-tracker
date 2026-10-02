# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`home-energy-tracker` is a learning project: event-driven Spring Boot microservices tracking home energy consumption from IoT devices. Java 21, Spring Boot 4.1.1 (Spring Framework 7), Jackson 3, JUnit 5, Lombok, Maven multi-module; PostgreSQL, Kafka (KRaft), InfluxDB 3 Core, Keycloak.

**Target architecture: `docs/design/00-energy-tracker-blueprint.md`** (the "blueprint"; a **local-only** file — deliberately not committed, ignored via `.git/info/exclude`, so it exists on the user's machine only; it supersedes `01-outbox-design.md` and `02-processed-events-design.md`, which it grew out of). Eight services, database per service, transactional outbox + `processed_events`, an account-deletion saga by choreography, device tokens + a compacted registry topic for ingestion, cumulative meter readings in InfluxDB, tumbling-window alerts, a notification queue, AI over read-only tools, Spring Cloud Gateway. Build toward it, section by section (`§2.1` = user-service, etc.). Don't copy blueprint detail into this file — point at its sections. **Before implementing anything the blueprint specifies, check "Deviations from the blueprint" below**: some differences are deliberate, some are still open decisions for the user.

**Work order (user's plan):** finish user-service against §2.1 with real test coverage (§10), then rebuild device-service (§2.2), then the rest. device-, ingestion- and usage-service in the repo today predate the blueprint and will be rebuilt, not patched — don't invest in their current code.

**Design principle: model real IoT energy devices as accurately as reasonable** rather than a convenient simplification (e.g. the cumulative meter counter, blueprint D3).

| Service | Port today | Blueprint port | State |
|---|---|---|---|
| api-gateway | — | 8000 | not started |
| user-service | 8080 | 8081 | active — see "user-service" below |
| device-service | 8081 | 8082 | legacy, to be rebuilt next |
| ingestion-service | 8082 | 8083 | legacy |
| usage-service | 8083 | 8084 | legacy |
| alert-service | — | 8085 | not started |
| notification-service | — | 8086 | not started |
| ai-insight-service | — | 8087 | not started |

## Layout

One Maven reactor: root `pom.xml` (packaging `pom`, parented on `spring-boot-starter-parent` 4.1.1) aggregates every module and holds the only Maven wrapper. Each module's `pom.xml` is parented on the root (`<relativePath>../../pom.xml</relativePath>`) and lists dependencies without versions. Root `<dependencyManagement>` imports the Spring Cloud, Spring AI, Resilience4j (2.4.0) and Testcontainers (2.0.5) BOMs and manages `contracts` at `${project.version}`.

- `contracts/` — plain jar (Lombok compile-only, no Spring): `EventEnvelope<T>`, `EventHeaders` (`event_id`, `event_type`, `schema_version`), and `user/`: `UserEvents` (topic `user.events.v1`, aggregate type `User`, producer `user-service`), `UserEventType`, `UserRegisteredData`, `UserUpdatedData`, `UserDeletionRequestedData` (each with a `SCHEMA_VERSION` constant). Used by user-service. See "Kafka event contract".
- `services/<name>/` — one module per service.
- `infra/` — `docker-compose.yml`, `.env.example`, `.env` (gitignored), `keycloak/realm-energy.json` (+ README), `kafka/kafka-topics.sh`. All container config lives here.
- `e2e/` — Layer 4 suite (Testcontainers `ComposeContainer`); stub, zero tests; only in the reactor under `-Pe2e`.
- `docs/design/` — the blueprint (00, local-only, never commit it) and its two committed predecessors (01, 02).
- `scripts/get-token.ps1 -Role user|admin` — prints a Keycloak access token for the seeded test/admin user.

## Commands

### Infrastructure (from `infra/`)
```bash
docker compose up -d                         # user-db, keycloak-db, keycloak
docker compose --profile kafka up -d         # + kafka broker, kafka-topics-init (one-shot, exits 0), kafka-ui (:8070)
docker compose --profile influxdb up -d      # + influxdb (legacy usage-service)
docker compose --profile kafka down -v       # --profile goes BEFORE the subcommand; without it the kafka containers are left running
```
`.env` is read only by docker compose. Running a service from the IDE/`mvnw` needs its `${...}` secrets exported in the shell — user-service needs `USER_DB_PASSWORD`. The dev machine needs `127.0.0.1 keycloak` in its hosts file (the token's `iss` is pinned to `http://keycloak:8180`).

### Build (root wrapper; `.\mvnw.cmd` in PowerShell)
```bash
./mvnw test                                                      # unit tests (*Test) only, every module - no Docker needed
./mvnw verify                                                    # + integration tests (*IT, Testcontainers) via Failsafe - needs Docker
./mvnw -pl services/user-service -am test                        # one service (-am builds contracts first; without it contracts must be installed)
./mvnw -pl services/user-service -am test -Dtest=UserServiceTest # one unit test class
./mvnw -pl services/user-service -am verify -Dit.test=UserSchemaIT -Dfailsafe.failIfNoSpecifiedTests=false -Dtest=NoUnitTests -Dsurefire.failIfNoSpecifiedTests=false  # one IT class only
./mvnw -pl services/user-service -am spring-boot:run             # run one service
```

### Local JWT
`scripts/get-token.ps1`, or `POST http://keycloak:8180/realms/energy-tracker/protocol/openid-connect/token` with `grant_type=password`, `client_id=local-dev`, `client_secret=secret`, `username=testuser@example.com`, `password=Testpassword1`. Use as `Authorization: Bearer <access_token>`.

## Keycloak (local dev realm)

`infra/keycloak/realm-energy.json` is imported on first boot only into an **empty** Keycloak — editing it needs `docker compose down -v` before the next `up`. Realm `energy-tracker`; client `local-dev` (confidential, direct-grant enabled, secret `secret` committed on purpose — throwaway dev credential, stand-in for the future gateway/`energy-cli` clients) with an audience mapper adding `user-service` to `aud`; realm roles `USER`, `ADMIN`; seeded `testuser@example.com`/`Testpassword1` (id `11111111-…`, role USER) and `admin@example.com`/`Adminpassword1` (id `22222222-…`, role ADMIN). Fixed ids are for this local realm only — blueprint §1.3.4 forbids deterministic ids in any real realm. Keycloak runs on host port 8180 (8080 was user-service's). These names change to the blueprint's during saga step 5 — see "Names" under Deviations.

## Kafka event contract

- **User/device events (`user.events.v1`, later `device.events.v1`, …) use the shared `contracts` module.** The value is the JSON-serialized `EventEnvelope<T>` (blueprint §3.4) — no Java class names, no `__TypeId__`. `eventType`/`aggregateType` are `String`s in the envelope so consumers survive new types; producers use the `UserEventType` enum's `value()`. Consumers read the envelope with `data` as a tree, dispatch on `eventType` (also in the `event_type` header), then convert `data`; unknown fields are ignored (Jackson 3 default); unknown `schemaVersion` → DLT. Key = `aggregateId` (text; `StringSerializer`, never `UUIDSerializer`).
- **Consumers** (blueprint §3.3): one consumer group per service named after it; record-level ack after the side effect; `processed_events (event_id, handler)` inserted in the same transaction; poison/deserialization errors → `<topic>.dlt` via `ErrorHandlingDeserializer` + `DeadLetterPublishingRecoverer`; `DataAccessException`s retry indefinitely (never DLT — skipping breaks ordering); `concurrency ≤ partitions`, no `@Async` in listeners.
- **Topics are created by `infra/kafka/kafka-topics.sh`** (run by the `kafka-topics-init` compose service, `--if-not-exists`, broker auto-create off): `user.events.v1` 3 partitions, RF 1 (one broker). Every blueprint topic (§3.5) and its DLT goes in this script, never a `NewTopic` bean. Partition counts are permanent once a broker has real data (§17.9). Use dots only in topic names (Kafka warns that `.`/`_` collide in metric names).
- Legacy exception: `energy-usage-events` (legacy ingestion → usage) still uses per-service `NewTopic` beans and per-side event copies with `spring.json.type.mapping`; it disappears when those services are rebuilt onto `energy.readings.v1`.

## Conventions

- **Comments.** Learning project — heavy explanatory comments are intentional; don't strip them as cleanup. (The user sometimes deletes comment blocks on purpose after saving them elsewhere; don't flag that.) Never write "see CLAUDE.md" pointers in code — explain inline.
- **Schema.** Flyway owns all DDL; `ddl-auto=validate`; changes are new forward migrations, never edits to an applied `V` file. Names: `pk_/uq_/chk_/idx_<table>_…`; `CURRENT_TIMESTAMP` over `now()`. user-service lets **the database own time**: `created_at` default + a `BEFORE UPDATE` trigger stamping `updated_at` (V6), mapped `@Generated` (`insertable/updatable = false`); the trigger leaves `version` alone, so native `UPDATE`s bump `version = version + 1` themselves. Other services adopt the same when rebuilt.
- **State transitions** are compare-and-set: every status-changing `UPDATE` carries `WHERE status = '<expected>'` and reports rows affected (`int`). The database constraint (PK, partial unique index) is the real uniqueness guarantee; any app pre-check only improves the error message.
- **Entities.** Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor`; fields with defaults need `@Builder.Default`; exclude lazy associations from `equals/hashCode/toString`.
- **Outbox writer rules.** `OutboxService.record*` methods are `@Transactional(propagation = MANDATORY)` (must join the business transaction). Only the request that actually changed the row emits (check the native query's row count, or the entity's version before/after `saveAndFlush`). Build the event from the row as the database now holds it (re-read after a native update); `occurredAt` = the row's DB-stamped `created_at`/`updated_at`/`deletion_requested_at`. The event id is generated once, in the envelope, and the outbox columns are derived from that same envelope. `aggregate_version` has gaps (bumps that emit nothing) — consumers compare with `>`, never `previous + 1`.
- **Never call a `@Transactional`/`@Scheduled`/aspect-advised method via `this.`** — self-invocation bypasses Spring's proxy silently. (It happened once: an old relay self-called its own `markPublished`, so `published_at` was never persisted.) Put the method on another bean.
- **Web.** One `@RestControllerAdvice GlobalExceptionHandler` per service returning `ProblemDetail`; user-service's is the reference (extends `ResponseEntityExceptionHandler`; `type` under `https://home-energy-tracker/problems/`, `title`, `status`, `detail`, `instance`, `timestamp` on every error, same as the security 401/403 handlers; `OptimisticLockingFailureException` → 409; catch-all 500 logs the cause and rethrows `AccessDeniedException`/`AuthenticationException`). DTOs are records with `@Builder` and `@JsonPropertyOrder` (Jackson 3 orders alphabetically and matches names verbatim, camelCase). Bean Validation on request DTOs + `@Valid`. User identity comes only from the JWT `sub`; ownership is `WHERE user_id = :sub` in the query and a miss is 404, never 403 (§7.5).
- **Config.** `@Value` into `@Bean` methods or constructor parameters (not onto a `final` field with `@RequiredArgsConstructor` — see gotchas). Secrets only from env vars. `application.yaml` (spaces only — YAML forbids tabs).
- **Logging.** Blueprint §14.1: never log tokens, secrets, emails or request bodies; log ids. (The old `LoggingAspect`/`ExecutionTimeAspect`/`@SkipLogging` were removed 2026-09-28 — they logged arguments and return values, including emails. `spring-boot-starter-aspectj` stays in the pom for Resilience4j's annotations.) Logging/metrics/tracing come from Micrometer + structured logging (step 6).
- **Git.** The user usually commits; only commit when told. Commit messages: meaningful title + "-" bullet description; keep `Co-Authored-By`, never add a `Claude-Session` trailer. Never read, print or verify `infra/.env` in any way (docker compose reading it internally is fine).

## Framework and tooling gotchas (verified in this repo)

- Boot 4 starter renames: `spring-boot-starter-security-oauth2-resource-server`; `spring-boot-starter-aspectj` (Boot 4 no longer manages `spring-boot-starter-aop`); `@DataJpaTest` lives in `spring-boot-starter-data-jpa-test`.
- Testcontainers 2.x: modules are `testcontainers-postgresql`, `testcontainers-junit-jupiter`, …; `org.testcontainers.postgresql.PostgreSQLContainer`; `@ServiceConnection` overrides `spring.datasource.*`.
- Jackson 3: `tools.jackson.databind.*` (annotations still `com.fasterxml.jackson.annotation`); unknown properties ignored by default; `writeValueAsString` throws unchecked.
- Spring 7: `ProblemDetail.getType()` may be null (Spring 6 defaulted to `about:blank`); trailing-slash URLs no longer match; `@Min/@Max` on `@RequestParam` validates natively (`HandlerMethodValidationException`). Standalone MockMvc needs `AuthenticationPrincipalArgumentResolver` for `@AuthenticationPrincipal`. `PageImpl` silently corrects inconsistent totals; an unpaged page reports size = content size.
- Hibernate: a native `@Modifying` query bypasses the persistence context — an entity loaded earlier in the same transaction stays stale; use `clearAutomatically = true` (detaches everything) then re-read. Hibernate auto-flushes pending changes before native queries; `JdbcTemplate` does not (flush first). `saveAndFlush` of an unchanged entity — or a setter with the same value — issues no `UPDATE` and no version bump. In `@DataJpaTest` (one transaction per test): flush before JDBC reads, clear before entity re-reads.
- Lombok: `@Value` on a `final` field + `@RequiredArgsConstructor` → Spring looks for a bean of type `int` (Lombok doesn't copy the annotation to the constructor parameter).
- PostgreSQL: row locks are held until commit/rollback, and a blocked `UPDATE` re-evaluates its `WHERE` against the committed row (READ COMMITTED) — the basis of every CAS here. `pg_try_advisory_xact_lock(key)` is non-blocking, released at transaction end, scoped per database, not tied to any table; callable as a Spring Data native query returning `boolean`. `CURRENT_TIMESTAMP` is the transaction start time (tests that insert then update in one transaction must seed old timestamps). Unquoted identifiers fold to lowercase. pgjdbc 42.7 and `JdbcTemplate` cannot bind a `java.time.Instant` parameter (both handle `OffsetDateTime`, the JDBC 4.2 type for `timestamptz`); reading one back as `Instant.class` works.
- Spring Boot 4.1.1 ships structured logging (`logging.structured.format.console=ecs|gelf|logstash`, MDC included). `resilience4j-spring-boot3:2.4.0` compiles against Boot 4.1.1 with `spring-boot-starter-aspectj` (runtime interception not yet verified). A flat dotted key under `logging.level` works in YAML.
- Spring Kafka's default dead-letter topic is `<topic>-dlt` (checked against spring-kafka source, per legacy usage-service's `KafkaTopicConfig`); the blueprint names them `<topic>.dlt`, so consumers need a custom destination resolver.
- `apache/kafka:4.3.1`: CLI at `/opt/kafka/bin/`, entrypoint ends in `exec "$@"` (override `command` only), runs as `appuser`.
- Git Bash on Windows: prefix `docker exec/run` with `MSYS_NO_PATHCONV=1` when passing absolute container paths.

## user-service (active)

### Implemented
- **Schema**: V1–V4 are history; V5 resets around `users.id = sub` (UUID, never generated), `status ACTIVE/DELETING/DELETED`, `deletion_requested_at`, `deleted_at`, `devices_deleted`, `keycloak_disabled_at`, `version`, `chk_users_status`, `chk_users_email`, `chk_users_deleted_consistency`, partial `uq_users_email` (lower(email), non-DELETED), `idx_users_deleting`; V6 `updated_at` trigger; V7 `outbox_events` (§3.1 shape; payload NOT NULL and must be a JSON object); V8 `outbox_events.parked` (`chk_outbox_events_parked_unpublished`), payload < 256 KB, pending index `WHERE published_at IS NULL AND NOT parked`, `idx_outbox_events_parked`; V9 `users.keycloak_deleted_at`, `chk_users_deleting_consistency`, `chk_users_keycloak_disabled_not_active`, `chk_users_keycloak_deleted_only_deleted`; V10 `processed_events` (§3.3; no consumer uses it yet). Each column is explained in the migration comments.
- **Provisioning** (`UserProvisioningService.ensureUsable`, run by `UserProvisioningInterceptor` before every `/api/**` controller): `insertIgnoringConflict` (`ON CONFLICT (id) DO NOTHING`, R8) → re-read → `UserRegistered` only from the winning insert; display name from `name` → `given_name` → `preferred_username` local part; email re-sync from the token (only when `email_verified`) via native CAS `syncEmail` (`clearAutomatically`) → re-read → `UserUpdated`; any non-ACTIVE user gets 403 `AccountNotActiveException`.
- **API**: `GET /api/v1/users/me`; `PATCH /api/v1/users/me` (`displayName` ≤100, non-blank, stripped; `timezone` unvalidated; `@Version` → 409; `UserUpdated` only if the version moved); `DELETE /api/v1/users/me` (`markDeleting` native CAS `WHERE status='ACTIVE'`, version+1, `clearAutomatically` → re-read → `UserDeletionRequested`; 202, empty body). Admin (role ADMIN): `GET /api/v1/admin/users` (paged, `size` ≤100, sort `createdAt DESC, id`) and `/{id}`.
- **Security**: resource server with `issuer-uri`, `AudienceValidator` (`aud` ∋ `user-service`), `KeycloakRealmRoleConverter`, ProblemDetail 401/403 handlers, stateless, CORS for `localhost:3000`; `/actuator/health` public (only `health` exposed).
- **Outbox + relay**: `OutboxService` (see Outbox writer rules); `relay/OutboxRelay`: `@Scheduled(fixedDelayString = app.outbox.relay.interval-ms)` + `@Transactional`, `OutboxRepository.advisoryLockAcquired(7_777_777)`, oldest-first batch skipping parked rows (`findByPublishedAtIsNullAndParkedFalseOrderBySeqAsc`; nothing parks a row automatically), `KafkaTemplate<String,String>`, key `aggregateId`, headers from `EventHeaders`, sends the whole batch first, then waits for the acks once against one batch deadline (`send-timeout-ms` 5000), marks the published prefix in `seq` order (`markPublished` int / `recordError`), stops at the first failure — later rows that were already delivered stay unpublished and are resent next tick, so the latest state is also last in the log (changed 2026-09-29 from per-record `send().get(5s)`; the topic comes from the row's `topic` column; verified live 2026-09-30 against real Postgres/Keycloak/Kafka: messages and headers in Kafka UI, every row marked published). Config: interval 2000 ms, batch 50; producer `acks=all`, idempotence on. The earlier per-record version was verified end-to-end 2026-09-27 against real Postgres/Keycloak/Kafka: all three event types published in commit order with correct key, headers and payload.

### Remaining to reach blueprint §2.1 (roadmap, rewritten 2026-09-29; recommended order; each step lands with its tests and a CLAUDE.md update)
Done so far this round: pipelined relay; config/logging clean-up (claim-map log removed from `UserProvisioningInterceptor`, security DEBUG logging and the explicit dialect dropped, `acks`/`enable.idempotence` under `spring.kafka.producer`, producer `max.block.ms: 5000`); step 0 "prove what exists" (2026-09-30): `UserRepositoryIT` native-query tests, `OutboxEventSchemaIT`, `OutboxServiceTest`, `OutboxServiceIT`, relay verified live.

1. ~~Migrations V8–V10~~ — done 2026-09-30 (one per concern: outbox parking, users saga consistency, `processed_events`; schema tests + `MigrationUpgradeIT`). A database created before V9 must hold no row breaking the new users checks, or Flyway stops at V9 on startup; check first with `SELECT id, status FROM users WHERE (status = 'ACTIVE') <> (deletion_requested_at IS NULL) OR (keycloak_disabled_at IS NOT NULL AND status = 'ACTIVE');`.
2. **Relay hardening** (§3.2), in small steps, each checked before the next (final design agreed 2026-10-02):
   - a. ~~Failsafe split~~ — done 2026-10-01 (see Testing strategy).
   - b. ~~Failure policy~~ — done 2026-10-02: no automatic parking (see "Relay failure policy" under Decided); `OutboxRelayTest` covers a failure in the middle of a batch (prefix marked, `recordError`, rest pending).
   - c. `RelayIT` against Testcontainers Kafka: key and headers, mark only after ack, broker down → nothing marked and nothing lost, two relays → only one publishes (advisory lock).
   - d. Two beans (a self-call would bypass the `@Transactional` proxy): the `@Scheduled` outer bean asks `CircuitBreaker relayProducer` for permission before any connection or lock; the inner transactional bean runs one batch. Breaker: window 5, minimum 5 calls, 100 % failure rate, 10–30 s open, 1 half-open probe; records only real send attempts (not empty or lock-lost ticks).
   - e. Drain loop: while a batch came back full and fully acknowledged, run the next one at once (up to ~10 per tick, each in its own short transaction); mark the published prefix in one `UPDATE ... WHERE seq = ANY(...)`.
   - f. Daily purge of rows published > 7 d ago (own `@Scheduled` bean, batched deletes).
   - g. `correlation_id` header (the value comes with step 6).
3. **HTTP contract and security** (§2.1, §7.5, §16):
   - Responses carry `status` and `version`; `If-Match` → 412; `email` in `PATCH` → 422; `timezone` validated against `ZoneId.getAvailableZoneIds()` (the TODO in `UpdateUserRequest`); `DELETE` → 202 `{status, requestedAt}`.
   - `AccountNotActiveException` → `AccountDeletedException`, 410 for any non-ACTIVE account on every endpoint (decided; also settles the TODO in `GlobalExceptionHandler`); user endpoints require the user role.
   - Tests: `@WebMvcTest` + `jwt()` status/security matrix (401 no/expired token, wrong `aud`, 403 wrong role, 404/409/410/412/422, admin endpoints), unit tests for `AudienceValidator` and `KeycloakRealmRoleConverter`.
4. **Contract fixtures** (§10.3.5): `contracts/src/test/resources/events/<EventType>.v<N>.json` for the three existing events; producer tests serialize and compare. Before the saga, so `UserDeletionFinalized` gets its fixture when it is added.
5. **Deletion saga** (§2.1 finaliser, §4, §6.1, §7.3; order in "Account-deletion saga (decided)" below):
   - Renames first (see "Names" below; realm `down -v`).
   - `UserDeletionFinalized` in `contracts` (type, data, `SCHEMA_VERSION`, fixture).
   - Keycloak admin client (Resilience4j `keycloakAdmin`: TimeLimiter 3 s, CircuitBreaker, Retry 2 only on idempotent calls, none on the in-request attempt); disable + logout after the `DELETING` commit → `keycloak_disabled_at`; 60 s retry job.
   - `device.events.v1` consumer for `UserDevicesDeleted` (consumer rules under "Kafka event contract"; raw-SQL flag flip `WHERE status='DELETING'`, no version bump — race U6; `processed_events`; `.dlt` destination resolver); topic + DLT added to `kafka-topics.sh`.
   - Finaliser (`FOR UPDATE SKIP LOCKED`, 10-min grace, scrub, `DELETED`, outbox event); Keycloak hard delete → `keycloak_deleted_at` + retry job; watchdog metric/log for `DELETING` rows older than a threshold.
   - Tests: finaliser claim disjointness from two threads, U1–U6 (§8.1), Keycloak down (WireMock) → still 202 + retry, consumer idempotency and DLT with hand-crafted messages (device-service doesn't exist yet).
6. **Runtime config and observability** (§1.4, §14): `open-in-view: false` (Boot defaults it to true today), Hikari sizes, `statement_timeout` in the JDBC URL, virtual threads, graceful shutdown, `jwk-set-uri` split from `issuer-uri` (removes the hosts-file need once containerized), actuator `prometheus` + §2.1 metrics (incl. outbox lag, parked count, `DELETING` age), `logging.structured.format.console: ecs`, Micrometer tracing → Tempo and `correlation_id` (open decision 2), `userId` in MDC.
7. **Replay** (§5.6): `UserSnapshot` event (+ fixture) + `POST /api/v1/admin/users/replay-snapshots`; outbox replay by aggregate id / seq range.
8. **Container** (§9.1, §9.2): Dockerfile + compose service with readiness health check.
9. **Later, with the rest of the system**: load tests (§13) to settle relay tuning (open decision 3) and the cost of provisioning on every request (the TODO in `UserProvisioningInterceptor`); e2e (§10, `e2e/` module) once device-service consumes `user.events.v1`.

## Legacy services (device, ingestion, usage)

Current code implements the pre-blueprint pipeline: ingestion publishes per-tick deltas to `energy-usage-events`; usage consumes into InfluxDB (`energy_readings`) with a polled `DeviceIdCache` from device-service; device-service is plain CRUD validating users over REST with `@CreationTimestamp`/`@UpdateTimestamp`. All three are replaced by blueprint §2.2–§2.4 (device tokens + `device.registry.v1`, `energy.readings.v1` with cumulative `energy_total_kwh` and a `user_id` tag, usage as sole InfluxDB owner with the reset-aware calculator). Don't extend them.

## Deviations from the blueprint

### Decided (keep)
- **Deletion event is `UserDeletionRequested`** (blueprint: `UserDeleted`), with empty `data` — the request time is the envelope's `occurredAt` (blueprint puts `deletionRequestedAt` in `data`). Already in `contracts` and emitted; consumers built later must use this name.
- **`updated_at` via DB trigger** (V6) instead of `updated_at = now()` in each statement; `pk_/uq_/chk_/idx_` naming; outbox table named `outbox_events`.
- **Provisioning on every `/api/**` request** (blueprint: `GET /me` only), and email sync only when `email_verified` is true.
- **Audience validation** (`aud` ∋ `user-service`) — optional in the blueprint.
- **Admin read endpoints** (`GET /admin/users`, `/admin/users/{id}`) beyond the blueprint; keep them minimal (§17.2).
- **Relay send mode** (2026-09-29): pipelined batch + one wait, instead of the blueprint's per-record `.get(5 s)` (§3.2). Same guarantees (order within a partition, mark only after ack, stop at first failure, all inside the locked transaction), about one network round trip per batch instead of one per record. Not `whenComplete` callbacks: they run on the producer's network thread after the transaction has committed and the advisory lock is gone.
- **Relay failure policy: stall and alert, never skip** (2026-10-02; replaces the blueprint's "park after 20 failed attempts" and this file's earlier "park permanent errors immediately" rule — both turn a delay into a loss: a parked `UserUpdated` with an email change leaves notification-service mailing the old address, because user-service's own row already holds the new email and the `GET /me` re-sync never fires again; a parked `UserDeletionRequested` leaves the user `DELETING` forever). An outbox event may arrive late, twice or slightly out of order, never not at all. Every send failure stops the batch at the failed row (earlier rows marked published, the failed row gets `attempts + 1` and `last_error`, later rows stay pending and are resent — duplicates are absorbed by the consumers' version guards and `processed_events`). A misconfiguration (topic ACL, ...) stalls delivery and alerts (`outbox_oldest_pending_age_seconds`); fixing it drains the backlog in order. Poison rows are prevented at write time instead (payload < 256 KB check, constant topic, `StringSerializer`). The `parked` column and the `NOT parked` filter stay for a manual operator quarantine added with the replay endpoint (step 7, together with `UserSnapshot` so consumers converge); nothing parks a row automatically.
- **Topic provisioning script** `infra/kafka/kafka-topics.sh` + `kafka-topics-init` = the blueprint's `kafka-init`. Kafka image `apache/kafka:4.3.1` (blueprint 3.9.0); Postgres 16 (blueprint 17 — bump with "Postgres topology" below).
- **Non-ACTIVE accounts get 410 everywhere** (2026-09-28). `AccountNotActiveException` → `AccountDeletedException`, 410, problem type `account-deleted`, for `DELETING` and `DELETED` on every endpoint including a repeated `DELETE /me` (blueprint answers 202 for a repeated `DELETE` on `DELETING` — the difference is cosmetic; the resulting state is the same, so `DELETE` stays idempotent). Keep `requestDeletion`'s `markDeleting == 0` branch: two parallel `DELETE`s can both pass the interceptor while `ACTIVE`; the loser returns quietly (202, no second event).

### Account-deletion saga (decided 2026-09-28)
Blueprint §6.1 with these choices: the database change always commits first and every Keycloak call happens after the commit, made idempotent and retried through a timestamp column.
1. `DELETE /me`: one tx `ACTIVE → DELETING` + outbox `UserDeletionRequested` → 202.
2. After that commit, in the same request: Keycloak disable + logout (one attempt, short timeout, no in-request retry) → `keycloak_disabled_at`. Keycloak down → still 202; a job retries every 60 s while `status='DELETING' AND keycloak_disabled_at IS NULL`.
3. device-service emits `UserDevicesDeleted` → user-service sets `devices_deleted = true`. Status stays `DELETING`. user-service waits for no other service (alert/notification/ai cleanup is idempotent and eventually consistent; `UserDeletionFinalized` covers late creations).
4. Finaliser every 60 s (`FOR UPDATE SKIP LOCKED`): `status='DELETING' AND devices_deleted AND keycloak_disabled_at < now() - grace`; grace = **10 minutes, measured from the Keycloak disable** (access tokens live 5 min — `accessTokenLifespan: 300`; disable + logout stop new ones, so after 10 min no token for the user is valid anywhere, even if Keycloak was down for a while). In one tx: scrub email to `deleted+<id>@invalid` and display name to `Deleted user`, `DELETED`, `deleted_at`, version+1, outbox `UserDeletionFinalized`. **Email and display name are scrubbed only here** (the earlier "tombstone the email at DELETING" plan is dropped): until then the address stays reserved in our DB and in Keycloak (`duplicateEmailsAllowed: false`).
5. After that commit: Keycloak hard delete → `keycloak_deleted_at`; a job retries while `status='DELETED' AND keycloak_deleted_at IS NULL`.
6. device-/alert-service rerun their deletion on `UserDeletionFinalized` (catches anything created with a still-valid token before their tombstone landed — race D2).
Deletion is irreversible; the grace is a safety delay, not a cancel window. A watchdog on old `DELETING` rows catches a saga stuck in a DLT.

### Names (decided 2026-09-28: switch in one go at the start of step 5)
Realm `energy-tracker` → `energy` (issuer `…/realms/energy`); roles `USER`/`ADMIN` → `user`/`admin`/`service`; client `local-dev` → `energy-cli` (public, direct grant, dev only), plus `energy-ui` (public, PKCE), `energy-services` (confidential, role `service`), `energy-admin` (confidential, `realm-management` `manage-users` + `view-users` only); audience mapper moves to `energy-cli` if audience validation stays; user-service port 8080 → 8081 (blueprint ports in the table above); config prefix `app.*` → `energy.user.*`. Touches the realm JSON + README, `application.yaml`, role strings in `SecurityConfig`/`@PreAuthorize`, `JwtFixtures`/tests, `scripts/get-token.ps1`, this file; needs `down -v`. Keycloak stays on host port 8180.

### Open (user decides; recommendation given)
1. **Postgres topology.** Blueprint: one `postgres:17` container, one database + role per service (and Keycloak's), created by init SQL. Today: dedicated `user-db` (db `user_service`) and `keycloak-db` containers. *Recommendation:* switch when device-service is rebuilt (rename `user_service` → `users_db` then too).
2. **Correlation ids / tracing.** Earlier plan here (2026-09-27): a hand-written `OncePerRequestFilter` putting a UUID into MDC, copied into `outbox_events.correlation_id` by `OutboxService`. Blueprint §14.1: Micrometer Tracing (OTLP → Tempo), W3C `traceparent` over HTTP and Kafka, MDC filled by the tracing bridge, `correlationId` = the originating trace id. *Recommendation:* blueprint — `OutboxService` stores the request's trace id in `correlation_id` (the relay runs in its own trace), the relay forwards it as a header; `causation_id` stays an explicit parameter set by consumers. Also put the JWT `sub` into MDC in `UserProvisioningInterceptor` (removed in `afterCompletion`) so every log line carries `userId`.
3. **Relay tuning.** Blueprint 300 ms / 200 rows; today 2000 ms / 50 (kept small while proving the relay). Revisit with load tests (§13).

## Testing strategy

Target: blueprint §10 (test matrix §10.2, what each type must prove §10.3, security tests §10.4). **Unit/integration split (2026-10-01):** a class that needs Docker (Testcontainers) is named `*IT` and runs in Failsafe (`integration-test` phase, after `package`); everything else is `*Test` and runs in Surefire. Failsafe is declared once in the root pom (configured by the Boot parent); Surefire needs no declaration. Test style here: names `method_scenario_outcome`, shared fixtures (`UserFixtures`, `JwtFixtures`; personas Arthur Morgan, Leon Kennedy, John Marston, Jack Carver, Jason Brody), explicit imports, explanatory comments; every timestamp is an `Instant` (decided 2026-10-01; JDBC parameters go through `testsupport/JdbcParameters.bindable(...)`, which converts at the boundary); verify behaviour empirically (run it / mutation-check a test by breaking the code) rather than assume.

**user-service today:**
- Unit (Mockito, no Spring): `UserServiceTest`, `UserProvisioningServiceTest` (incl. lost-race → no event), `UserAdminServiceTest`, `UpdateUserRequestTest`, `GlobalExceptionHandlerTest`; controllers via standalone MockMvc (`UserControllerTest`, `UserAdminControllerTest` — `@PreAuthorize` and URL security rules are not covered this way).
- Repository/DB (`@DataJpaTest` + `replace = NONE` + `@Import(testsupport/PostgresTestContainerConfig)` — `postgres:16-alpine` via `@ServiceConnection`, shared through Spring's context cache): `UserSchemaIT` (PK, NOT NULLs, defaults, every CHECK incl. V9's, `uq_users_email`, V6 trigger), `UserRepositoryIT` (mapping incl. a full DELETED-row round trip, `@Version`, stale copy → `OptimisticLockingFailureException`, paging order, and every outcome of the native `insertIgnoringConflict`/`markDeleting`/`syncEmail`: row counts, CAS guards, constraint violations, detached entities after `clearAutomatically`). `OutboxEventSchemaIT` (plain SQL: `seq` identity and order, `uq_outbox_events_id`, defaults, NOT NULLs, every CHECK, jsonb payload, V8's parked/payload-size checks, the partial index definitions via `pg_indexes`; one `insertEvent(overrides)` helper). `ProcessedEventSchemaIT` (V10: PK per `(event_id, handler)`, `ON CONFLICT DO NOTHING` returns 0 on redelivery). `OutboxRepositoryIT` (relay batch query skips published and parked rows, `seq` order, batch size). `MigrationUpgradeIT` (no Spring: its own container, Flyway to V7 in a fresh schema, rows inserted, then upgraded to latest; an inconsistent V7 row makes V9 fail while V8 stays applied). `OutboxServiceIT` (`@DataJpaTest` + `@ImportAutoConfiguration(JacksonAutoConfiguration)` + `@Import(OutboxService)`, i.e. Boot's real `ObjectMapper`: `MANDATORY` → `IllegalTransactionStateException`, and each `record*` after the real native query writes one row whose `occurred_at` equals the user's DB timestamp and the payload's `occurredAt`).
- Unit: `OutboxServiceTest` (Mockito + a plain `JsonMapper`: row columns and payload come from one envelope, the right timestamp per event, a fresh event id per call, `UserDeletionRequested` carries no email/name). `OutboxRelayTest` (Mockito `OutboxRepository` + `KafkaTemplate` returning completed/failed futures; the relay is built through its constructor).
- `UserRepositoryConcurrencyIT`: empty placeholder for the race tests (U1–U6 and friends), not written yet.
- Missing (blueprint §10.2 row for user-service): Kafka (use Testcontainers Kafka, per the blueprint — not `@EmbeddedKafka`), API/security matrix (`@WebMvcTest` + `jwt()`), concurrency (U1–U6, two relays, two finalisers), contract, failure-path (Keycloak/Kafka down), e2e.
- Concurrency tests: opt out of `@DataJpaTest`'s rolled-back transaction (`@Transactional(propagation = NOT_SUPPORTED)`), give each thread its own transaction (`TransactionTemplate` + `REQUIRES_NEW`), clean up afterwards, and force the overlap deterministically (hold A's transaction open, wait until B is blocked — e.g. `pg_stat_activity` via Awaitility — then commit A) rather than relying on timing.

**CI** (`.github/workflows/ci.yml`): one job, `./mvnw --batch-mode --no-transfer-progress verify` on `ubuntu-latest` (Docker present for Testcontainers): Surefire runs `*Test`, then Failsafe runs `*IT`. `-Dtest='**/*Test,**/*Tests,!**/*ApplicationTests'` excludes the legacy services' `*ApplicationTests` (they need a live Postgres and Keycloak issuer); the include patterns must be listed because `-Dtest` replaces Surefire's defaults — an exclusion alone made Surefire run every `*IT` too. Still to come from §10.1: contract stage, images, e2e.

## Maintenance

**Never let this file go stale.** When code, a design decision or a plan diverges from what is written here, update CLAUDE.md in the same change. When an open deviation is decided, move it to "Decided" (or delete it if the blueprint's version was adopted and is now implemented).

A separate running list of production-readiness gaps is kept in Claude's project memory — surface it only when the user asks.
