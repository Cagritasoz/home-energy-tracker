# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## What this is

`home-energy-tracker` is a learning project: four independent Spring Boot microservices tracking home energy consumption, wired together by Kafka events, one shared PostgreSQL instance (schema per service), and InfluxDB for time-series readings. Java 21, Spring Boot 4.1.1, Jackson 3, JUnit 5, Lombok.

There is **no parent/aggregator POM** — each service is a standalone Maven project with its own wrapper.

**Design principle: model real IoT energy devices as accurately as reasonable**, not a convenient simplification. This has already driven one real design change — see "Reading model" in the Roadmap — and should keep informing future choices (e.g. device behavior, failure modes) over whatever is easiest to fake.

| Service | Port | Role |
|---|---|---|
| `user-service` | 8080 | CRUD for users; alert rules (in progress); will own the outbox + user events |
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
- `user-service` / `device-service` have AOP aspects: `LoggingAspect` (service method entry/exit/throw) and `ExecutionTimeAspect` (controller timing). These are log lines only, not exported metrics.

### Uniqueness enforcement (the standard pattern here)
Application-level `existsBy…` pre-check for a clean 409 **plus** a DB unique constraint as the real guarantee (races that slip past the pre-check hit the `DataIntegrityViolationException` handler). See `UserService.createUser` + `existsByEmail`.

## Coding conventions (respect these when making changes)

- **Naming.** Pick the clearest, most accurate name for every class, field, local variable, method, and endpoint — and keep it **uniform across all four services**, not just locally consistent. Established patterns: methods `create{E}` / `get{E}ById` / `get{E}s` / `update{E}` / `delete{E}`; controller locals `created{E}` / `found{E}` / `updated{E}`; request DTO `{E}Request`, response `{E}Response` (older code still has a single `{E}Dto` — the split is the target); `{E}NotFoundException` / `Duplicate{X}Exception`; layer-first packages (`controller`, `service`, `repository`, `entity`, `dto`, `model`, `exception`, `config`, `aspect`, `event`). Check how sibling services name a concept before introducing a name, and call out inconsistent names in code under review.
- **Comments.** This is a learning project — heavy explanatory comments are intentional and stay until the final product. Explain non-obvious decisions and anything the user may not already know. Do not strip comments as "cleanup."
- **Entities.** Lombok `@Data @Builder @NoArgsConstructor @AllArgsConstructor`. Any field with a default value needs `@Builder.Default`, or the builder path writes `null` into a `NOT NULL` column. Exclude lazy `@ManyToOne` fields from `equals`/`hashCode`/`toString`.
- **Config.** `@Value` into `@Bean` factory methods (`RestClientConfig`, `KafkaTopicConfig`, `InfluxDBConfig`); topics are declared as `NewTopic` beans (broker auto-create is off).
- **Secrets** stay in `.env` (gitignored). Never hardcode credentials or paste tokens into code/commits.
- **Never call a `@Transactional` (or otherwise AOP-advised) method via plain `this.` from inside its own class.** Spring implements `@Transactional`/`@Scheduled`/aspect advice via a proxy wrapping the bean; a self-invocation bypasses that proxy entirely, so the annotation silently does nothing - no error, just quietly-wrong behavior (this actually happened: `OutboxRelay` self-calling its own `markPublished` meant `published_at` was never really persisted, so every tick would have re-published the same rows forever - fixed by moving that method onto `OutboxService` and calling it as a genuine cross-bean call). If a method needs its own transaction/advice, put it on a different bean and call it from there.

## Roadmap (planned / in progress — keep this current)

- **Alert rules (in progress, user-service).** `AlertRule` entity + repo + `AlertRuleController` + `AlertRuleService` under `/api/v1/users/{userId}/alert-rules`. `V3` migration adds `alert_rules` with `evaluation_window` (bounded enum, now carrying a human-facing `label` too), `threshold_kwh NUMERIC(10,3)`, `scope` (only `ALL_DEVICES` today), a unique `(user_id, evaluation_window, scope)`, and FK `ON DELETE CASCADE`. Deferred columns: `scope_ref`, `version`. `User`/`UserRequest`/`UserResponse`/`UserService`/`UserController` are done: the old single `UserDto` and the `alertsEnabled`/`energyAlertingThreshold` fields are gone, replaced by the `UserRequest`/`UserResponse` split (matching `AlertRuleRequest`/`AlertRuleResponse`) and `createdAt`/`updatedAt` audit columns.
- **user-service → Kafka via the outbox pattern — implemented, not yet run against a live broker.** `outbox_events` table (`V4`), `OutboxEvent` entity, and the topic (`user-domain-events`, `KafkaTopicConfig`, 1 partition/1 replica) all exist. `OutboxService.recordEvent(...)` is called from inside `UserService`/`AlertRuleService`'s own `@Transactional` methods (create/update/delete) to write a row in the same transaction as the domain change — never call `kafkaTemplate.send()` directly instead, that's the dual-write problem this pattern exists to avoid. Payloads: `UserChangedPayload` (covers both `USER_CREATED`/`USER_UPDATED`), `UserDeletedPayload`, `AlertRuleChangedPayload` (covers both `ALERT_RULE_CREATED`/`ALERT_RULE_UPDATED`), `AlertRuleDeletedPayload` — one shape per outcome, not per event type, since `OutboxEvent.eventType` already says which happened. Payload timestamps are always `Instant.now()` captured in the service method, never `entity.getUpdatedAt()` — `@UpdateTimestamp` only stamps its new value at flush/commit, which hasn't happened yet at the point the event is built.
- **`OutboxRelay` (`@Scheduled`, `app.outbox.relay.interval-ms`/`batch-size`) is the producer** — polls `outbox_events` for `published_at IS NULL` rows (oldest first, bounded batch, backed by `V4`'s partial index) and publishes each with `KafkaTemplate<Long, String>` (`LongSerializer`/`StringSerializer` — `payload` is already a JSON string, sending it through `JacksonJsonSerializer` again would double-encode it). Three headers per record: `event-type`, `aggregate-type`, `outbox-event-id`. **Consumers must filter on these headers, not by deserializing the payload** — that's what makes filtering actually cheap (a header read costs nothing extra; deserializing every record just to discard most of them defeats the point). `published_at` is only set *after* `kafkaTemplate.send(...).get()` confirms the write - never on a fire-and-forget send, which would let an undelivered row look identical to a delivered one. The relay processes rows in strict `id` order and **stops entirely on the first failed send** rather than skipping it - skipping would let a later event for the same `partitionKey` reach Kafka before an earlier one, reversing the ordering `partitionKey` exists to guarantee. A permanently-poison row blocking the whole batch is the accepted downside (mitigated later via `V4`'s deferred `attempts`/`last_error` columns, not built).
- **Deliberately one topic (`user-domain-events`), not split by aggregate type.** `alert_rules.user_id` cascades via a DB-level `ON DELETE CASCADE`, invisible to the outbox-writing code — so `UserDeleted` is the *only* event a consumer caching alert-rule state will ever get for "this user's rules are gone too," and it must arrive correctly ordered relative to that user's earlier `AlertRule*` events. Kafka only orders within a partition, never across topics, so splitting into `user-events`/`alert-rule-events` would silently reintroduce that race (a consumer could see `UserDeleted` before a still-in-flight `ALERT_RULE_CREATED`, permanently orphaning a cache entry). `AlertRule` is also a child of the `User` aggregate (no independent REST existence), so one topic honestly describes the domain rather than being a workaround.
- **`OutboxEvent.partitionKey` is always the owning user's id, even for `ALERT_RULE_*` rows** (not that rule's own `aggregateId`, which stays the specific row's own id for traceability — the two are deliberately separate columns). This is what keeps a user and all their alert rules on the same Kafka partition, and therefore strictly ordered, at **any** partition count, not just today's single one — raising partition count later for throughput is then safe without reopening the ordering question.
- **Consumer idempotency.** device-service (and later others) subscribe to `user-domain-events`; delivery is at-least-once, so every consumer must be idempotent (`DELETE WHERE user_id = ?` is). This — not any cross-service cascade — is how a deleted user's devices get cleaned up.
- **Multiple independent consumers on one topic/partition is fine - as long as each uses its own `group.id`.** Kafka fans a topic out per consumer *group*, not per partition; a single partition only caps how many instances *within one group* can parallelize, it does not limit how many independent groups can each read every message. Every future consumer of `user-domain-events` (device-service, usage-service, alerting-service) needs its own `spring.kafka.consumer.group-id` matching its own service name - same pattern usage-service already uses for `energy-usage-events` (`group-id=usage-service`, bound explicitly via `@KafkaListener(..., groupId = "${spring.kafka.consumer.group-id}")`). Two services sharing one `group.id` would silently split messages between them instead of both receiving everything - the one real way to break this. Each consumer also has to filter by `event_type`/`aggregate_type` since the shared topic carries every event kind, not just the ones it cares about.
- **Reading model — cumulative counter, not per-tick delta (decided, not yet implemented).** Real smart meters / IoT energy monitors report a monotonically increasing energy register (like an odometer), not "energy used this instant" — utility billing itself works by subtracting two register readings. `EnergyUsageDto` (ingestion-service) and both independent `EnergyUsageEvent` copies will change `consumedEnergy` → **`cumulativeEnergyKwh`** (validated `@PositiveOrZero`, not `@Positive`); the InfluxDB point field renames `consumed_energy` → `cumulative_energy_kwh`. `ContinuousDataSimulator` needs to change from a stateless per-tick random delta to **per-device running state** (a running total incremented each tick). Bonus this buys: the pipeline becomes self-healing against a lost/DLQ'd message — the next successful reading already reflects everything consumed since the last one, unlike the old delta model where a lost message loses that interval's energy forever. **Known problem, deliberately deferred:** a device reboot resets its counter to 0, which a naive subtraction reads as a large negative delta — needs detection before this is relied on; revisit later.
- **usage-service scheduled alert evaluation (query design for the cumulative model).** A fixed-cadence `@Scheduled` job. Per-user usage over a rule's `evaluation_window` = (last known cumulative reading **as of `now`**) − (last known cumulative reading **as of `now − window`**), each an "as-of" query summed per device then grouped by `user_id` — standard SQL via `ROW_NUMBER() OVER (PARTITION BY device_id ORDER BY time DESC)` filtered to `time <= boundary`, not a special interpolate/gap-fill feature (whether InfluxDB 3 Core exposes one is unverified — don't assume it without checking the actual version's docs). The `now` boundary is shared by every window, so it's computed **once per tick**, not once per window: ≈1 + (distinct windows in use, ≤5) queries per tick, still flat in user count. A **max-staleness cap** (a usage-service app property, not a per-rule column — a data-quality setting, not a user preference) rejects an as-of reading too old to trust rather than silently using a stale number; open decision whether that excludes just the stale device from the sum or skips the whole rule that cycle. True linear interpolation between straddling readings is optional future polish on top of this, not required for v1.
- **usage-service caches for the above.** (1) snapshot of enabled alert rules from user-service (poll like `DeviceIdCache`, or event-driven); (2) device→user mapping — expand `DeviceIdCache` from an id set to `Map<userId, Set<deviceId>>`, and write `user_id` as an InfluxDB tag at ingest so the `GROUP BY user_id` works; (3) per-rule firing / edge-trigger state (under→over transition, last-fired) held locally in usage-service, **not** in `alert_rules`.
- **alerting-service (new service).** Consumes `ThresholdExceededEvent` and delivers notifications.
- **Keycloak + API gateway (deferred phase).** Added later. Endpoints stay nested for now; then add `keycloak_id` to `users`, move `userId` from a path variable to the token identity, and keep the per-request ownership check (`findByIdAndUserId`), just re-sourced.

## Maintenance

**Never let this file go stale.** When code, a design decision, or a stated plan diverges from what is written here, update `CLAUDE.md` in the same change.

A separate running list of missing best-practices / production-readiness gaps is tracked in Claude's project memory — surface it only when the user asks.
