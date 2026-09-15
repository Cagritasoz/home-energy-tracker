# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`home-energy-tracker` is a learning project: four independent Spring Boot microservices tracking home energy consumption, wired together by Kafka events, one shared PostgreSQL instance (schema per service), and InfluxDB for time-series readings. Java 21, Spring Boot 4.1.1, Jackson 3, JUnit 5, Lombok.

There is **no parent/aggregator POM** — each service is a standalone Maven project with its own wrapper.

**Design principle: model real IoT energy devices as accurately as reasonable**, not a convenient simplification. This has already driven one real design change — see "Reading model" in the Roadmap — and should keep informing future choices (e.g. device behavior, failure modes) over whatever is easiest to fake.

| Service | Port | Role |
|---|---|---|
| `user-service` | 8080 | CRUD for users + alert rules; publishes both via the outbox pattern (feature-complete — see "user-service — status") |
| `device-service` | 8081 | CRUD for devices; validates the owning user exists via REST to user-service |
| `ingestion-service` | 8082 | Accepts energy readings (HTTP + built-in simulator), publishes them to Kafka |
| `usage-service` | 8083 | Consumes readings, writes to InfluxDB; will run scheduled alert evaluation |

## Commands

### Infrastructure (from repo root)
```bash
cp .env.example .env          # then fill in real values
docker compose up -d          # postgres, kafka (KRaft), kafka-ui (:8070), influxdb
docker exec -it influxdb influxdb3 create token --admin   # one-time; export result as INFLUXDB3_AUTH_TOKEN
```
Each service's Flyway creates its own schema (`user_service`, `device_service`) on first run because `spring.flyway.schemas` is set; Flyway owns all DDL after that.

### Per service (from the service directory, e.g. `user-service/`)
```bash
./mvnw spring-boot:run                          # run (use .\mvnw.cmd in PowerShell)
./mvnw clean package                            # build + test
./mvnw test                                     # all tests
./mvnw test -Dtest='UserServiceApplicationTests#contextLoads'   # single test
```

- **`.env` is only read by docker compose.** Running a service via `mvnw` needs `POSTGRES_DB`, `POSTGRES_USER`, `POSTGRES_PASSWORD` (and for usage-service `INFLUXDB3_AUTH_TOKEN`) exported into the shell — `application.properties` resolves them as `${...}`.
- **usage-service** requires `--add-opens=java.base/java.nio=ALL-UNNAMED` (InfluxDB 3 client's Arrow Flight SQL path). `spring-boot:run` injects it automatically; a packaged jar needs it passed to `java` directly.

## Architecture

### The reading pipeline
`ingestion-service` — via `POST /api/v1/ingestion` or the `@Scheduled` `ContinuousDataSimulator` (toggle `simulation.enabled`) — builds an `EnergyUsageEvent` and publishes it to Kafka topic `energy-usage-events`, keyed by `deviceId`. Publishing is fire-and-forget (telemetry latency must not wait on broker ack).

`usage-service` consumes on a single listener thread, tags each reading with `deviceKnown` (looked up in `DeviceIdCache`), and writes an InfluxDB `Point` (`energy_readings` measurement in the `energy_usage` database). A write failure propagates out of the listener so the container's `DefaultErrorHandler` retries, then routes to `energy-usage-events-dlt`. An **unknown deviceId is never dropped** — the cache is advisory and eventually consistent; dropping real telemetry over a stale cache would lose data.

### Kafka event contract (important)
Producer and consumer **each define their own copy** of the event record — they do not share a module. The wire contract is the logical alias in `spring.json.type.mapping` (`energy-usage-event`), written into the `__TypeId__` header; neither service depends on the other's packages. `ErrorHandlingDeserializer` wraps the delegate deserializers so a poison message goes to the DLQ instead of wedging the consumer forever. Apply this same pattern to every future topic.

### Cross-service coupling is deliberately minimal
- **No foreign keys across schemas.** `devices.user_id` has no DB FK — the relationship is enforced in code. (A FK *is* correct *within* a schema: `alert_rules.user_id → users.id ON DELETE CASCADE`.)
- **REST calls have explicit timeouts** (`RestClientConfig` in device-service and usage-service). A hung dependency surfaces as `ResourceAccessException` → 503, never an exhausted thread pool.
- **`DeviceIdCache`** (usage-service) polls `GET /api/v1/devices/ids` every 30s into a `volatile` immutable snapshot; a failed refresh keeps the last good snapshot rather than wiping to empty.

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

## user-service — status: feature-complete for CRUD + eventing; Debezium is the next upgrade

Users and alert rules (CRUD, validation, ownership/uniqueness rules) plus publishing both as domain events via the outbox pattern are done — verified by cross-checking every column against every entity mapping and by compiling after every change, **and manually confirmed working end-to-end** (2026-09-13): create/update/delete on both users and alert rules, `outbox_events` rows inserting correctly, Kafka messages and headers matching expectations in `kafka-ui`, and deleting a user correctly cascading their `alert_rules`. No bugs found in manual testing. This is still **manual, one-off, and not repeatable** — it proves the design works today, not that it keeps working as the code keeps changing. Zero automated tests exist for any of it (see Known gaps).

**What works:**
- Full CRUD for `User` and `AlertRule` under consistent naming (`create{E}`/`get{E}ById`/`get{E}s`/`update{E}`/`delete{E}`, `{E}Request`/`{E}Response`), matching device-service's own conventions.
- Schema and code verified in lockstep across `V1`–`V4` — every column checked against its entity mapping at each step, no drift found.
- Ownership boundaries reasoned through individually, not defaulted: a real FK for `alert_rules.user_id → users.id ON DELETE CASCADE` (intra-service), no FK anywhere cross-service, and deliberately no FK on `outbox_events.aggregate_id` either — it can legitimately reference an already-deleted row.
- The outbox pattern implemented with the properties that make it actually reliable, not just present: `OutboxService.recordEvent` joins the caller's transaction (atomic with the domain change, never a separate `kafkaTemplate.send()`); `partitionKey` is always the owning user's id — even for `ALERT_RULE_*` rows, never that row's own id — so a user's events stay strictly ordered across aggregate types at **any** partition count; `published_at` is only set after a confirmed Kafka ack, never on fire-and-forget; the relay stops the whole batch on the first failed send rather than risk a later event overtaking an earlier one; consumers filter on Kafka headers (`event-type`/`aggregate-type`/`outbox-event-id`) instead of deserializing every message just to discard most of them; one topic (`user-domain-events`), deliberately not split by aggregate type, because `AlertRule` cascade-deletes invisibly to the outbox writer and `UserDeleted` is the only signal a rule-state consumer will ever get for that.
- One real bug found and fixed along the way: `OutboxRelay` self-invoking its own `@Transactional markPublished` via `this.` silently bypassed Spring's proxy, so `published_at` was never actually persisted — fixed by moving all `outbox_events` persistence into `OutboxService`, called cross-bean (see Coding conventions' self-invocation rule).
- Kafka producer reliability (`enable.idempotence=true`, `acks=all`) pinned explicitly rather than left to the client's (currently correct) defaults.

**Known gaps, specific to user-service:**
- **Manually verified once, zero automated coverage.** The 2026-09-13 manual pass (above) covers the happy path a human thought to try - not concurrent requests, not partial failures (send-succeeds-then-mark-fails, a Kafka outage mid-batch), not the CHECK constraints actually rejecting bad values, not regressions from the next change. See the Testing Strategy section for the plan to close this.
- **Nothing consumes `user-domain-events` yet** — device-service doesn't subscribe. The outbox produces; nothing reacts, so "deleting a user cleans up their devices" isn't real end-to-end yet (building that consumer is device-service's work).
- **`LoggingAspect`/`@SkipLogging` fix frequency, not content.** `@SkipLogging` correctly silenced `OutboxRelay`'s per-tick noise, but `OutboxService.recordEvent` — correctly still logged, it's a genuine per-action event — logs its full `payload` argument at INFO. That's the same PII already flagged for `UserRequest`/`AlertRuleRequest` now exposed a second time through a different path. `LoggingAspect` has no notion of "log that this happened, mask the sensitive fields" — it's binary (fully logged, or `@SkipLogging`'d entirely) because it was written when user-service was CRUD-only and every logged argument was already raw user input; it hasn't been revisited since the service grew a second, event-shaped reason to log things. Needs field-level redaction or structured logging with an explicit allow-list to actually fix — not done, flagged only.
- Zero test coverage, now covering meaningfully more surface (`OutboxService`, `OutboxRelay`, every outbox-writing call site) than before.
- Deferred by design, documented at the point of deferral: `AlertRule.scope_ref`/`version`; `outbox_events.attempts`/`last_error`/`last_attempted_at`; `trace_id`/`correlation_id` (project-wide, not started anywhere).
- Pre-existing, unrelated to this build: no optimistic locking, case-sensitive email uniqueness, hard delete only (mitigated intra-service by the FK cascade; still a cross-service gap the outbox exists to close once a consumer exists), no pagination on `getUsers`, no idempotency-key support on POST.

**Next planned upgrade:** replace the `@Scheduled` polling relay with Debezium/CDC reading `outbox_events` off the Postgres WAL directly — `V4`'s column shape (`aggregate_type`/`aggregate_id`/`event_type`/`payload`) was deliberately chosen to match Debezium's outbox-event-router convention for exactly this migration. Not started.

## Testing strategy (0% coverage today — the plan for closing it)

Manual testing (see user-service status above) proves the design works once, for the paths a human thought to try — it doesn't run again on the next change, and can't cover concurrent requests or partial failures. Layer tests so most never need the full `docker-compose` stack — only a handful of true cross-service journeys do.

- **Layer 1 — unit tests** (no I/O, no Spring context, milliseconds each). Pure logic and Mockito-mocked repositories: `AlertRuleService.resolveName()` against various inputs; the window-changed uniqueness-check branch in `updateAlertRule`; `DuplicateEmailException`/`*NotFoundException` thrown on the right conditions; `GlobalExceptionHandler` methods called directly with a constructed exception, asserting the `ProblemDetail`. Nothing here touches a database or a broker.
- **Layer 2 — repository/persistence integration tests** (`@DataJpaTest` + Testcontainers Postgres, one service's own DB only). A real, throwaway Postgres container per run, Flyway migrations applied against it exactly like production (this doubles as a migration-correctness check). Tests: `UserRepository`/`AlertRuleRepository`/`OutboxEventRepository` query methods; `V3`/`V4`'s CHECK constraints actually rejecting bad values via a raw insert; `ON DELETE CASCADE` actually removing `alert_rules` when a user is deleted — automating exactly what was manually verified above. No other service needed.
- **Layer 3 — service-layer integration tests** (`@SpringBootTest` + Testcontainers Postgres + `@EmbeddedKafka` where Kafka is involved). `spring-boot-starter-kafka-test`/`-webmvc-test`/`-data-jpa-test` are already dependencies in these `pom.xml` files, unused — this is what they're for. Tests: `UserService.createUser` persists both the `User` row and its `OutboxEvent` row in one transaction; `deleteUser` cascades and writes `UserDeletedPayload`; `OutboxRelay` against an embedded broker — correct headers, `published_at` only set after a confirmed send, the batch stopping on the first failed send rather than reordering. Still only one service's own containers — `@EmbeddedKafka` needs no other service running to test a producer's own output, or a future consumer's own reaction to a hand-crafted message on the topic.
- **Layer 4 — cross-service / end-to-end tests** (the real `docker-compose` stack, kept deliberately small). The only layer that actually needs multiple services running together — reserved for a handful of true journeys (e.g. create a user, create their device, delete the user, confirm the device is gone once device-service has a consumer), not exhaustive coverage. Slower and more fragile by nature; most coverage should live in Layers 1–3, this layer only confirms the wiring.

**Build order given 0% today:** start with user-service (it just reached a stable point) — Layers 1–2 first, then Layer 3 for the outbox/relay. Build device-service's upcoming `UserDeleted` consumer *with* tests from day one rather than retrofitting them later. Backfill the other services opportunistically, prioritizing whatever has already stopped changing (don't sink effort into deeply testing `EnergyUsageEvent.consumedEnergy` right before the cumulative-counter rename lands). Add the Layer 4 suite last, once there's a second service to actually react to something.

**CI (GitHub Actions, not built yet):** a matrix job per service (`user-service`/`device-service`/`ingestion-service`/`usage-service`, matching the no-parent-POM structure) running `./mvnw test` — GitHub-hosted runners have Docker preinstalled, so Testcontainers-backed tests need no extra CI setup. A separate, less-frequent job (e.g. only on `main`) runs `docker compose up -d` for the Layer 4 suite — `/actuator/health` (not yet added, see gaps) would make "wait until services are ready" reliable there instead of ad hoc port polling.

## Roadmap (planned / in progress — keep this current)

- **Cross-service contract for `user-domain-events` consumers (device-service, usage-service, alerting-service — none built yet).** Delivery is at-least-once, so every consumer must be idempotent (`DELETE WHERE user_id = ?` is) — this, not any cross-service cascade, is how a deleted user's devices are meant to get cleaned up. Each consumer needs its **own** `spring.kafka.consumer.group-id` matching its own service name (same pattern usage-service already uses for `energy-usage-events`) — Kafka fans a topic out per consumer *group*, not per partition, so a single partition doesn't limit how many independent groups can each read every message, but two services sharing one `group.id` would silently split messages between them instead of both receiving everything. Each consumer also filters by the `event-type`/`aggregate-type` headers since the shared topic carries every event kind, not just the ones it cares about.
- **Reading model — cumulative counter, not per-tick delta (decided, not yet implemented).** Real smart meters / IoT energy monitors report a monotonically increasing energy register (like an odometer), not "energy used this instant" — utility billing itself works by subtracting two register readings. `EnergyUsageDto` (ingestion-service) and both independent `EnergyUsageEvent` copies will change `consumedEnergy` → **`cumulativeEnergyKwh`** (validated `@PositiveOrZero`, not `@Positive`); the InfluxDB point field renames `consumed_energy` → `cumulative_energy_kwh`. `ContinuousDataSimulator` needs to change from a stateless per-tick random delta to **per-device running state** (a running total incremented each tick). Bonus this buys: the pipeline becomes self-healing against a lost/DLQ'd message — the next successful reading already reflects everything consumed since the last one, unlike the old delta model where a lost message loses that interval's energy forever. **Known problem, deliberately deferred:** a device reboot resets its counter to 0, which a naive subtraction reads as a large negative delta — needs detection before this is relied on; revisit later.
- **usage-service scheduled alert evaluation (query design for the cumulative model).** A fixed-cadence `@Scheduled` job. Per-user usage over a rule's `evaluation_window` = (last known cumulative reading **as of `now`**) − (last known cumulative reading **as of `now − window`**), each an "as-of" query summed per device then grouped by `user_id` — standard SQL via `ROW_NUMBER() OVER (PARTITION BY device_id ORDER BY time DESC)` filtered to `time <= boundary`, not a special interpolate/gap-fill feature (whether InfluxDB 3 Core exposes one is unverified — don't assume it without checking the actual version's docs). The `now` boundary is shared by every window, so it's computed **once per tick**, not once per window: ≈1 + (distinct windows in use, ≤5) queries per tick, still flat in user count. A **max-staleness cap** (a usage-service app property, not a per-rule column — a data-quality setting, not a user preference) rejects an as-of reading too old to trust rather than silently using a stale number; open decision whether that excludes just the stale device from the sum or skips the whole rule that cycle. True linear interpolation between straddling readings is optional future polish on top of this, not required for v1.
- **usage-service caches for the above.** (1) snapshot of enabled alert rules from user-service (poll like `DeviceIdCache`, or event-driven); (2) device→user mapping — expand `DeviceIdCache` from an id set to `Map<userId, Set<deviceId>>`, and write `user_id` as an InfluxDB tag at ingest so the `GROUP BY user_id` works; (3) per-rule firing / edge-trigger state (under→over transition, last-fired) held locally in usage-service, **not** in `alert_rules`.
- **alerting-service (new service).** Consumes `ThresholdExceededEvent` and delivers notifications.
- **Keycloak + API gateway (deferred phase).** Added later. Endpoints stay nested for now; then add `keycloak_id` to `users`, move `userId` from a path variable to the token identity, and keep the per-request ownership check (`findByIdAndUserId`), just re-sourced.

## Maintenance

**Never let this file go stale.** When code, a design decision, or a stated plan diverges from what is written here, update `CLAUDE.md` in the same change.

A separate running list of missing best-practices / production-readiness gaps is tracked in Claude's project memory — surface it only when the user asks.
