# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`home-energy-tracker` is a learning project: four independent Spring Boot microservices tracking home energy consumption, wired together by Kafka events, one shared PostgreSQL instance (schema per service), and InfluxDB for time-series readings. Java 21, Spring Boot 4.1.1, Jackson 3, JUnit 5, Lombok.

**Maven reactor structure** (migrated from four standalone projects — see git history around 2026-09-15 for the restructure): one root `pom.xml` (packaging `pom`, parented on `spring-boot-starter-parent`) aggregates every module and holds the one Maven wrapper for the whole repo — services no longer have their own `mvnw`. Each service's `pom.xml` is parented on the root pom (`<relativePath>../../pom.xml</relativePath>`) and only lists dependencies, no versions. Layout:
- `contracts/` — plain jar (no Spring), meant to hold a shared event envelope/DTOs/topic constants/JSON examples. **Currently an empty stub, wired into the reactor but with no classes.** Populating it means reversing the "producer and consumer each define their own copy" decision below in "Kafka event contract" — don't add classes here until that tradeoff is actually decided, and rewrite that section in the same change once it is.
- `services/user-service`, `services/device-service`, `services/ingestion-service`, `services/usage-service` — the four services below, unchanged except for their POM's `<parent>`.
- `infra/` — `docker-compose.yml`, `.env.example`, `.env` (gitignored, as before). **All docker-related services and their properties live here now, going forward** — postgres/keycloak/prometheus/grafana/tempo config, once any of those exist, join it here rather than at repo root.
- `e2e/` — Layer 4 cross-service tests (Testcontainers `ComposeContainer` against `infra/docker-compose.yml`). **Stub: builds, zero test classes yet.** Only enters the reactor under the `-Pe2e` profile, so a plain `./mvnw test` never touches it.
- Planned, not yet built: `services/alert-service`, `services/notification-service`, `services/ai-insight-service`, `services/api-gateway` (see Roadmap), `scripts/`.
- Root `pom.xml` `<dependencyManagement>` pins BOMs for Spring Cloud, Spring AI, Resilience4j and Testcontainers — imported for the modules above that need them once they exist; nothing consumes Spring Cloud/Spring AI yet.

**Design principle: model real IoT energy devices as accurately as reasonable**, not a convenient simplification. This has already driven one real design change — see "Reading model" in the Roadmap — and should keep informing future choices (e.g. device behavior, failure modes) over whatever is easiest to fake.

| Service | Port | Role |
|---|---|---|
| `user-service` | 8080 | Owns user identity (Keycloak-backed) + alert rules; mid-rebuild around a new soft-delete/optimistic-locking schema — see "user-service — status" |
| `device-service` | 8081 | CRUD for devices; validates the owning user exists via REST to user-service |
| `ingestion-service` | 8082 | Accepts energy readings (HTTP + built-in simulator), publishes them to Kafka |
| `usage-service` | 8083 | Consumes readings, writes to InfluxDB; will run scheduled alert evaluation |

## Commands

### Infrastructure (from `infra/`)
```bash
cd infra
cp .env.example .env          # then fill in real values
docker compose up -d          # postgres, kafka (KRaft), kafka-ui (:8070), influxdb
docker exec -it influxdb influxdb3 create token --admin   # one-time; export result as INFLUXDB3_AUTH_TOKEN
```
Or from repo root: `docker compose -f infra/docker-compose.yml --env-file infra/.env up -d`.
Each service's Flyway creates its own schema (`user_service`, `device_service`) on first run because `spring.flyway.schemas` is set; Flyway owns all DDL after that.

### Build (one wrapper, at repo root — services no longer have their own)
```bash
./mvnw clean package                                              # build + test every module (use .\mvnw.cmd in PowerShell)
./mvnw test                                                       # all tests, every module
./mvnw -pl services/user-service -am spring-boot:run              # run one service (-am also builds its reactor dependencies, e.g. contracts once used)
./mvnw -pl services/user-service -am test -Dtest='UserServiceApplicationTests#contextLoads'   # single test
```
`-pl services/<name>` targets one module from the root; equivalently `cd services/<name>` and run `../../mvnw ...` from there. `-Pe2e` activates the `e2e/` Testcontainers-ComposeContainer suite (stub module — no tests yet).

- **`.env` is only read by docker compose.** Running a service via `mvnw` needs `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` (and for usage-service `INFLUXDB3_AUTH_TOKEN`) exported into the shell — `application.properties` resolves them as `${...}`.
- **usage-service** requires `--add-opens=java.base/java.nio=ALL-UNNAMED` (InfluxDB 3 client's Arrow Flight SQL path). `spring-boot:run` injects it automatically; a packaged jar needs it passed to `java` directly.

## Architecture

### The reading pipeline
`ingestion-service` — via `POST /api/v1/ingestion` or the `@Scheduled` `ContinuousDataSimulator` (toggle `simulation.enabled`) — builds an `EnergyUsageEvent` and publishes it to Kafka topic `energy-usage-events`, keyed by `deviceId`. Publishing is fire-and-forget (telemetry latency must not wait on broker ack).

`usage-service` consumes on a single listener thread, tags each reading with `deviceKnown` (looked up in `DeviceIdCache`), and writes an InfluxDB `Point` (`energy_readings` measurement in the `energy_usage` database). A write failure propagates out of the listener so the container's `DefaultErrorHandler` retries, then routes to `energy-usage-events-dlt`. An **unknown deviceId is never dropped** — the cache is advisory and eventually consistent; dropping real telemetry over a stale cache would lose data.

### Kafka event contract (important)
Producer and consumer **each define their own copy** of the event record — they do not share a module. The wire contract is the logical alias in `spring.json.type.mapping` (`energy-usage-event`), written into the `__TypeId__` header; neither service depends on the other's packages. `ErrorHandlingDeserializer` wraps the delegate deserializers so a poison message goes to the DLQ instead of wedging the consumer forever. Apply this same pattern to every future topic.

### Cross-service coupling is deliberately minimal
- **No foreign keys across schemas.** `devices.user_id` has no DB FK — the relationship is enforced in code. (A FK *was* correct *within* a schema when `alert_rules.user_id → users.id ON DELETE CASCADE` existed — `alert_rules` is dropped as of `V5` pending a UUID-native redesign; see "user-service — status". Re-apply the same intra-schema-FK-yes/cross-schema-FK-no rule once it comes back.)
- **REST calls have explicit timeouts** (`RestClientConfig` in device-service and usage-service). A hung dependency surfaces as `ResourceAccessException` → 503, never an exhausted thread pool.
- **`DeviceIdCache`** (usage-service) polls `GET /api/v1/devices/ids` every 30s into a `volatile` immutable snapshot; a failed refresh keeps the last good snapshot rather than wiping to empty.

### Identity (Keycloak)
`infra/keycloak/realm-energy.json` is imported into Keycloak automatically on first boot (`start-dev --import-realm`, mounted read-only) — it only imports into an *empty* Keycloak instance, so editing it requires `docker compose down -v` (dropping Keycloak's own storage) before the next `up` for the change to take effect. It pre-creates:
- Realm `energy-tracker`.
- Client `local-dev` (confidential, `directAccessGrantsEnabled` + `standardFlowEnabled` both on) — a **local-testing stand-in for the future api-gateway client**, not a real application. Its secret (`local-dev-secret`) is committed in that file on purpose — it's a throwaway local-dev credential, not a real one; treat it like any other value in this repo's `.env.example`, not like the `.env`-only rule in Coding conventions' Secrets bullet.
- Test user `testuser` / `testpassword`, with a **fixed** id (`11111111-1111-1111-1111-111111111111`) so its Keycloak subject — and therefore `users.id` — stays the same across every `down -v && up` reset during local dev.

**Getting a JWT locally** (direct access grant — username+password straight to Keycloak's token endpoint, no browser):
```bash
curl -X POST http://localhost:8180/realms/energy-tracker/protocol/openid-connect/token \
  -H "Content-Type: application/x-www-form-urlencoded" \
  -d grant_type=password \
  -d client_id=local-dev \
  -d client_secret=local-dev-secret \
  -d username=testuser \
  -d password=testpassword
```
The response's `access_token` is the JWT; its `sub` claim is `11111111-1111-1111-1111-111111111111` — that's the `users.id` value a first `GET /users/me` call would need a matching row for. Send it as `Authorization: Bearer <access_token>` on requests to user-service.

**user-service side:** `spring-boot-starter-security-oauth2-resource-server` (see the pom.xml comment — Boot 4.1.1 deprecated the non-`security-`-prefixed artifact name in favor of this one) + `spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:8180/realms/energy-tracker` + a `SecurityConfig` requiring authentication on every request. **No endpoint-specific authorization exists yet** because no endpoints exist yet (see "user-service — status") — this is resource-server wiring only, proven only to the extent that "every request needs a valid JWT" is enforced, not tested end-to-end against a real controller.
**Known gap, deliberately deferred:** no audience (`aud`) claim validation configured — Keycloak's default access-token audience is `account`, not scoped to a specific resource server. Not a problem with one standalone service and no gateway yet; revisit once api-gateway exists and multiple services need to trust *different* audiences from the same realm.
**Port 8180, not Keycloak's own default 8080** — 8080 is already user-service's port (see the service table above).

### Persistence
- One Postgres instance, **schema per service** (`currentSchema=` in the JDBC URL + `spring.flyway.schemas`), each with its own `flyway_schema_history`.
- **`spring.jpa.hibernate.ddl-auto=validate`** everywhere — entities must match the Flyway-managed schema exactly. Schema changes are **new forward migrations** (`V2…`, `V3…`); never edit an applied `V` file (checksum mismatch fails startup).
- Audit columns via `@CreationTimestamp` / `@UpdateTimestamp` (`Instant` ↔ `TIMESTAMPTZ`).

### Web layer conventions
- Each web service has one `@RestControllerAdvice GlobalExceptionHandler` returning `ProblemDetail`. Typed domain exceptions: `*NotFoundException` → 404, `Duplicate*Exception` → 409, `ResourceAccessException` → 503, `MethodArgumentNotValidException` → 400 with a field-keyed `errors` map, `HttpMessageNotReadableException` → 400.
- DTOs are Java **records** with `@Builder` and `@JsonPropertyOrder` (Jackson 3 orders alphabetically by default; it also matches JSON keys to field names **verbatim** — no snake_case conversion, so field names must be the exact wire names in camelCase).
- Bean Validation annotations live on request DTOs; `@Valid` on the controller parameter.
- `user-service` / `device-service` have AOP aspects: `LoggingAspect` (service method entry/exit/throw) and `ExecutionTimeAspect` (controller timing). These are log lines only, not exported metrics. Any method invoked on a fixed timer regardless of whether there's anything to do (`OutboxRelay.relay()`, and the `OutboxService` methods it calls every tick) should carry `@SkipLogging` (`user_service.aspect`) rather than be left to `LoggingAspect`'s default package-wide pointcut — each invocation there isn't a meaningful event the way a request-driven method's call is, unlike e.g. `OutboxService.recordEvent` (one call per real user action), which stays logged. Apply the same annotation to any future scheduled/polling method in a service that has a `LoggingAspect`.

### Uniqueness enforcement (the standard pattern here)
Application-level `existsBy…` pre-check for a clean 409 **plus** a DB unique constraint as the real guarantee (races that slip past the pre-check hit the `DataIntegrityViolationException` handler). See `UserService.createUser` + `existsByEmail`.

## Coding conventions (respect these when making changes)

- **Naming.** Pick the clearest, most accurate name for every class, field, local variable, method, and endpoint — and keep it **uniform across all four services**, not just locally consistent. Established patterns: methods `create{E}` / `get{E}ById` / `get{E}s` / `update{E}` / `delete{E}`; controller locals `created{E}` / `found{E}` / `updated{E}`; request DTO `{E}Request`, response `{E}Response` (older code still has a single `{E}Dto` — the split is the target); `{E}NotFoundException` / `Duplicate{X}Exception`; layer-first packages (`controller`, `service`, `repository`, `entity`, `dto`, `model`, `exception`, `config`, `aspect`, `event`). Check how sibling services name a concept before introducing a name, and call out inconsistent names in code under review.
- **Comments.** This is a learning project — heavy explanatory comments are intentional and stay until the final product. Explain non-obvious decisions and anything the user may not already know. Do not strip comments as "cleanup."
- **Entities.** Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor`. Any field with a default value needs `@Builder.Default`, or the builder path writes `null` into a `NOT NULL` column. Exclude lazy `@ManyToOne` fields from `equals`/`hashCode`/`toString`.
- **Config.** `@Value` into `@Bean` factory methods (`RestClientConfig`, `KafkaTopicConfig`, `InfluxDBConfig`); topics are declared as `NewTopic` beans (broker auto-create is off).
- **Secrets** stay in `.env` (gitignored). Never hardcode credentials or paste tokens into code/commits.
- **Never call a `@Transactional` (or otherwise AOP-advised) method via plain `this.` from inside its own class.** Spring implements `@Transactional`/`@Scheduled`/aspect advice via a proxy wrapping the bean; a self-invocation bypasses that proxy entirely, so the annotation silently does nothing - no error, just quietly-wrong behavior (this actually happened: `OutboxRelay` self-calling its own `markPublished` meant `published_at` was never really persisted, so every tick would have re-published the same rows forever - fixed by moving that method onto `OutboxService` and calling it as a genuine cross-bean call). If a method needs its own transaction/advice, put it on a different bean and call it from there.

## user-service — status: schema reset for Keycloak identity (2026-09-15); application code has not caught up yet

The CRUD-+-outbox build described in earlier versions of this file (`V1`–`V4`) was manually verified end-to-end and worked — but user identity was an app-generated `BIGSERIAL`, which doesn't survive fronting the system with Keycloak: the id a JWT's `sub` claim carries has to be the id every service uses, or every service needs an id-mapping lookup that doesn't otherwise need to exist. `V5` (2026-09-15) resets the schema around that: `users.id` is now the Keycloak subject (`uuid`, never DB-generated), plus a three-state soft-delete lifecycle (`status` `ACTIVE`/`DELETING`/`DELETED`, `deletion_requested_at`/`deleted_at`, `devices_deleted` as a saga-completion flag) and a `version` column doing double duty as JPA optimistic-locking *and* the event `aggregateVersion` once eventing is rebuilt. Every column's reasoning is in `V5`'s own comments — don't duplicate it here, read the migration.

**This was a deliberate, scoped decision, not a start-from-nothing reset:** `alert_rules` (FK'd to the old `users.id` type) and `outbox_events` (whose `aggregate_id`/`partition_key` can't stay a single `BIGINT` once one aggregate type's id is UUID) are dropped in the same migration rather than patched, since neither's UUID-native shape has been designed yet. They — plus a new `processed_events` table for idempotent event consumption — come back in later migrations once that design exists; don't guess at their shape ahead of that.

**Current state of the Java code: stale against the new schema, not yet touched.** `User`/`AlertRule`/`OutboxEvent` entities, their repositories/services/controllers/DTOs/exceptions, and `OutboxRelay`/`OutboxService` all still describe the pre-`V5` schema (`Long` id, `alert_rules`, `outbox_events`). They still compile (Java doesn't know about the DB), but **`spring.jpa.hibernate.ddl-auto=validate` will fail at Spring context startup** against the actual `V5` schema — the service cannot run yet. Rewriting them for the new schema and the target API surface below is the next work, not started.

**Target API surface (not implemented yet):** `GET /users/me`, `PATCH /users/me`, `DELETE /users/me` (soft: flips `status` to `DELETING`, sets `deletion_requested_at` — actual deletion is the async saga `V5`'s columns model, not an immediate row delete), admin `GET /users/{id}`. `userId` comes from the validated JWT's `sub`, not a path variable or request body — see "Identity (Keycloak)" above for how requests authenticate.

**Also done alongside the schema reset:** Keycloak is running in `infra/docker-compose.yml` and user-service has resource-server wiring (`SecurityConfig`, `issuer-uri`) requiring a valid JWT on every request — see "Identity (Keycloak)" above. This is infrastructure/security wiring only; it has no endpoint to protect yet.

**What's preserved from before, for when eventing is rebuilt (read, don't re-derive from scratch):** the outbox pattern's actually-reliable properties (`recordEvent` joins the caller's transaction, `published_at` only set after a confirmed Kafka ack, relay stops the batch on first failed send, consumers filter on Kafka headers rather than deserializing everything) and the one real bug already found and fixed once (`OutboxRelay` self-invoking `markPublished` via `this.` silently bypassed Spring's transactional proxy — see Coding conventions' self-invocation rule) are exactly the kind of mistake worth not re-making when `outbox_events` comes back.

**Known gaps carried forward:** zero automated test coverage (unchanged — see Testing strategy); `trace_id`/`correlation_id` still project-wide-not-started; `LoggingAspect` still has no field-level redaction (was flagged against `OutboxService.recordEvent`'s payload logging — moot until outbox eventing is rebuilt, but the aspect gap itself is unrelated to this reset and still real).

## Testing strategy (0% coverage today — the plan for closing it)

Manual testing (see user-service status above) proves the design works once, for the paths a human thought to try — it doesn't run again on the next change, and can't cover concurrent requests or partial failures. Layer tests so most never need the full `docker-compose` stack — only a handful of true cross-service journeys do.

- **Layer 1 — unit tests** (no I/O, no Spring context, milliseconds each). Pure logic and Mockito-mocked repositories: `AlertRuleService.resolveName()` against various inputs; the window-changed uniqueness-check branch in `updateAlertRule`; `DuplicateEmailException`/`*NotFoundException` thrown on the right conditions; `GlobalExceptionHandler` methods called directly with a constructed exception, asserting the `ProblemDetail`. Nothing here touches a database or a broker.
- **Layer 2 — repository/persistence integration tests** (`@DataJpaTest` + Testcontainers Postgres, one service's own DB only). A real, throwaway Postgres container per run, Flyway migrations applied against it exactly like production (this doubles as a migration-correctness check). Tests: repository query methods; `V5`'s CHECK constraints (`users_status_chk`, `users_email_chk`, `users_deleted_consistency_chk`) actually rejecting bad values via a raw insert; `users_email_uq` allowing a `DELETED` row's email to be reused. (The `alert_rules` `ON DELETE CASCADE` example this bullet used to cite no longer applies — that table is dropped as of `V5`; re-add an equivalent case once it's redesigned.) No other service needed.
- **Layer 3 — service-layer integration tests** (`@SpringBootTest` + Testcontainers Postgres + `@EmbeddedKafka` where Kafka is involved). `spring-boot-starter-kafka-test`/`-webmvc-test`/`-data-jpa-test` are already dependencies in these `pom.xml` files, unused — this is what they're for. Tests: `UserService.createUser` persists both the `User` row and its `OutboxEvent` row in one transaction; `deleteUser` cascades and writes `UserDeletedPayload`; `OutboxRelay` against an embedded broker — correct headers, `published_at` only set after a confirmed send, the batch stopping on the first failed send rather than reordering. Still only one service's own containers — `@EmbeddedKafka` needs no other service running to test a producer's own output, or a future consumer's own reaction to a hand-crafted message on the topic.
- **Layer 4 — cross-service / end-to-end tests** (the real `docker-compose` stack, kept deliberately small). The only layer that actually needs multiple services running together — reserved for a handful of true journeys (e.g. create a user, create their device, delete the user, confirm the device is gone once device-service has a consumer), not exhaustive coverage. Slower and more fragile by nature; most coverage should live in Layers 1–3, this layer only confirms the wiring.

**Build order given 0% today:** start with user-service (it just reached a stable point) — Layers 1–2 first, then Layer 3 for the outbox/relay. Build device-service's upcoming `UserDeleted` consumer *with* tests from day one rather than retrofitting them later. Backfill the other services opportunistically, prioritizing whatever has already stopped changing (don't sink effort into deeply testing `EnergyUsageEvent.consumedEnergy` right before the cumulative-counter rename lands). Add the Layer 4 suite last, once there's a second service to actually react to something.

**CI (GitHub Actions, not built yet):** a matrix job per service (`user-service`/`device-service`/`ingestion-service`/`usage-service`, matching the no-parent-POM structure) running `./mvnw test` — GitHub-hosted runners have Docker preinstalled, so Testcontainers-backed tests need no extra CI setup. A separate, less-frequent job (e.g. only on `main`) runs `docker compose -f infra/docker-compose.yml up -d` for the Layer 4 suite — `/actuator/health` (not yet added, see gaps) would make "wait until services are ready" reliable there instead of ad hoc port polling.

## Roadmap (planned / in progress — keep this current)

- **Cross-service contract for `user-domain-events` consumers (device-service, usage-service, alerting-service — none built yet).** Delivery is at-least-once, so every consumer must be idempotent (`DELETE WHERE user_id = ?` is) — this, not any cross-service cascade, is how a deleted user's devices are meant to get cleaned up. Each consumer needs its **own** `spring.kafka.consumer.group-id` matching its own service name (same pattern usage-service already uses for `energy-usage-events`) — Kafka fans a topic out per consumer *group*, not per partition, so a single partition doesn't limit how many independent groups can each read every message, but two services sharing one `group.id` would silently split messages between them instead of both receiving everything. Each consumer also filters by the `event-type`/`aggregate-type` headers since the shared topic carries every event kind, not just the ones it cares about.
- **Reading model — cumulative counter, not per-tick delta (decided, not yet implemented).** Real smart meters / IoT energy monitors report a monotonically increasing energy register (like an odometer), not "energy used this instant" — utility billing itself works by subtracting two register readings. `EnergyUsageDto` (ingestion-service) and both independent `EnergyUsageEvent` copies will change `consumedEnergy` → **`cumulativeEnergyKwh`** (validated `@PositiveOrZero`, not `@Positive`); the InfluxDB point field renames `consumed_energy` → `cumulative_energy_kwh`. `ContinuousDataSimulator` needs to change from a stateless per-tick random delta to **per-device running state** (a running total incremented each tick). Bonus this buys: the pipeline becomes self-healing against a lost/DLQ'd message — the next successful reading already reflects everything consumed since the last one, unlike the old delta model where a lost message loses that interval's energy forever. **Known problem, deliberately deferred:** a device reboot resets its counter to 0, which a naive subtraction reads as a large negative delta — needs detection before this is relied on; revisit later.
- **usage-service scheduled alert evaluation (query design for the cumulative model).** A fixed-cadence `@Scheduled` job. Per-user usage over a rule's `evaluation_window` = (last known cumulative reading **as of `now`**) − (last known cumulative reading **as of `now − window`**), each an "as-of" query summed per device then grouped by `user_id` — standard SQL via `ROW_NUMBER() OVER (PARTITION BY device_id ORDER BY time DESC)` filtered to `time <= boundary`, not a special interpolate/gap-fill feature (whether InfluxDB 3 Core exposes one is unverified — don't assume it without checking the actual version's docs). The `now` boundary is shared by every window, so it's computed **once per tick**, not once per window: ≈1 + (distinct windows in use, ≤5) queries per tick, still flat in user count. A **max-staleness cap** (a usage-service app property, not a per-rule column — a data-quality setting, not a user preference) rejects an as-of reading too old to trust rather than silently using a stale number; open decision whether that excludes just the stale device from the sum or skips the whole rule that cycle. True linear interpolation between straddling readings is optional future polish on top of this, not required for v1.
- **usage-service caches for the above.** (1) snapshot of enabled alert rules from user-service (poll like `DeviceIdCache`, or event-driven); (2) device→user mapping — expand `DeviceIdCache` from an id set to `Map<userId, Set<deviceId>>`, and write `user_id` as an InfluxDB tag at ingest so the `GROUP BY user_id` works; (3) per-rule firing / edge-trigger state (under→over transition, last-fired) held locally in usage-service, **not** in `alert_rules`.
- **alerting-service (new service).** Consumes `ThresholdExceededEvent` and delivers notifications.
- **Keycloak — started (2026-09-15); api-gateway itself still not built.** Keycloak runs locally (`infra/docker-compose.yml` + `infra/keycloak/realm-energy.json`, realm `energy-tracker`) and user-service validates JWTs against it (see "Identity (Keycloak)" above) — but there's no gateway yet, so `local-dev` (the realm's test client) is standing in for it. `users.id` *is* the Keycloak subject now (`V5`) — no separate `keycloak_id` column reconciling it against a app-generated id, unlike what an earlier version of this roadmap entry once sketched; there's no app-generated id left to reconcile against. Still ahead: api-gateway itself; moving `userId` from a path variable to token identity in every controller *that gets built* (none exist yet on the new schema — see "user-service — status"); per-request ownership checks (`findByIdAndUserId`-shaped) once there's a resource to own; audience (`aud`) claim validation once multiple services need to trust different audiences from the same realm (see "Identity (Keycloak)"'s known gap).

## Maintenance

**Never let this file go stale.** When code, a design decision, or a stated plan diverges from what is written here, update `CLAUDE.md` in the same change.

A separate running list of missing best-practices / production-readiness gaps is tracked in Claude's project memory — surface it only when the user asks.
