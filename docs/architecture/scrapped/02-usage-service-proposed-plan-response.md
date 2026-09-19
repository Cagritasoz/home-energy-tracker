# usage-service — Architectural Review

**Review date/time:** 2026-09-13 16:12 (UTC+03:00, Europe/Istanbul)
**Reviewed revision:** `9d9be1a` plus the uncommitted working-tree changes present at review time (user-service outbox `@SkipLogging` work, `../../../CLAUDE.md` status update, one comment added to `UsageService`).
**Input:** `01-usage-service-proposed-plan-prompt.md`
**Scope:** architecture and feasibility only. No application code, `../../../CLAUDE.md`, or the prompt file was modified.

This document is organised as the prompt requests (Sections 1–5), preceded by what the repository actually contains (Section 0), because several of the prompt's premises do not match the code. Throughout, every claim is tagged as one of:

- **[CURRENT]** — implemented and verified by reading the repository.
- **[PROPOSED]** — what the prompt describes as the plan.
- **[RECOMMENDED]** — what this review advises instead (or in addition).
- **[UNVERIFIED]** — a claim about an external system (InfluxDB 3 Core, Kafka defaults) that should be checked against the exact version you run before relying on it.

---

## 0. What the repository actually contains (inspection results)

The prompt describes usage-service as if it already had a local PostgreSQL, a Redis cache, event consumers for user/rule/device events, and a validation-and-reject step. **None of that exists.** The verified state:

### 0.1 Services, ports, dependencies

| Service | Port | Persistence | Kafka role | Notable dependencies |
|---|---|---|---|---|
| `user-service` | 8080 | Postgres schema `user_service` (Flyway V1–V4) | **Producer** of `user-domain-events` via transactional outbox | data-jpa, kafka, aspectj, validation |
| `device-service` | 8081 | Postgres schema `device_service` (Flyway V1 only) | **None** — `../../../pom.xml` has no Kafka starter at all | data-jpa, http-client (REST to user-service), aspectj |
| `ingestion-service` | 8082 | none | **Producer** of `energy-usage-events` | kafka, webmvc, validation |
| `usage-service` | 8083 | **none** — no data-jpa, no Flyway, no datasource | **Consumer** of `energy-usage-events`; producer to `energy-usage-events-dlt` only | kafka, webmvc, http-client, `influxdb3-java` 1.11.0 |

- `docker-compose.yml` runs exactly four containers: `postgres:16-alpine`, `apache/kafka:latest` (single KRaft node, `auto.create.topics=false`, retention left at the image default), `kafbat/kafka-ui`, `influxdb:3-core`. **There is no Redis container, no Redis client dependency, and no Redis property anywhere.** The only mention of Redis in the codebase is a comment in `ContinuousDataSimulator` speculating about a future cache.
- **Resilience4j is not a dependency of any service.** Neither is Spring Boot Actuator.
- No Testcontainers, no automated tests beyond the four generated `contextLoads` stubs.

### 0.2 Kafka topics and event contracts [CURRENT]

| Topic | Declared by | Partitions | Key | Value | Headers |
|---|---|---|---|---|---|
| `energy-usage-events` | ingestion-service **and** usage-service (both `NewTopic` beans, identical config) | 1 | `Long deviceId` | JSON `EnergyUsageEvent{deviceId, consumedEnergy, timestamp}` via `JacksonJsonSerializer`, type alias `energy-usage-event` | `__TypeId__` |
| `energy-usage-events-dlt` | usage-service | 1 | raw bytes | raw bytes of the failed record | Spring `DLT_*` diagnostic headers |
| `user-domain-events` | user-service | 1 | `Long partitionKey` = **owning user id, always** (also for `ALERT_RULE_*`) | outbox `payload` JSON string sent verbatim (`StringSerializer`) | `event-type`, `aggregate-type`, `outbox-event-id`. **No `__TypeId__`** — a consumer must dispatch on `event-type` and parse the JSON itself |

Event types on `user-domain-events`: `USER_CREATED`, `USER_UPDATED`, `USER_DELETED`, `ALERT_RULE_CREATED`, `ALERT_RULE_UPDATED`, `ALERT_RULE_DELETED`. Payloads: `UserChangedPayload{userId, firstName, lastName, email, address, occurredAt}`, `UserDeletedPayload{userId, occurredAt}`, `AlertRuleChangedPayload{ruleId, userId, name, evaluationWindow, thresholdKwh, scope, enabled, occurredAt}`, `AlertRuleDeletedPayload{ruleId, userId, occurredAt}`.

Important contract facts verified in code:

- `occurredAt` is `Instant.now()` captured **inside the service method, before commit**. It is not a commit timestamp and not a per-aggregate version.
- There is **no per-aggregate version number** in any payload and no `version` column on `users` or `alert_rules` (explicitly deferred in V3's header comment).
- Deleting a user cascades `alert_rules` at the DB level; **no `ALERT_RULE_DELETED` events are emitted for cascaded rules**. `USER_DELETED` is the only signal. This is documented and intentional.
- **There are no device events of any kind.** device-service has no outbox, no Kafka dependency, no `outbox_events` table, and no audit columns on `devices` (`V1` has only `id, device_name, device_type, location, user_id`).
- Device ownership is immutable: `DeviceService.updateDevice` throws `DeviceOwnerImmutableException` if `userId` changes. This is a useful property for partitioning (Section 4).

### 0.3 The transactional outbox (user-service) [CURRENT]

Verified properties of `OutboxService` + `OutboxRelay`:

- `recordEvent` joins the caller's transaction (`REQUIRED`), so the domain row and the outbox row commit atomically. Correct.
- `OutboxEvent.id` is `BIGSERIAL`, assigned at INSERT time (Hibernate `IDENTITY` → immediate insert), i.e. **assigned before the transaction commits**.
- The relay polls every 2 s, batch 100, `WHERE published_at IS NULL ORDER BY id`, publishes with a blocking `send().get()`, then `markPublished` in a separate transaction, and **stops the batch on first failure**. This gives at-least-once delivery with in-id-order publication.
- `enable.idempotence=true`, `acks=all` pinned explicitly.

One correctness subtlety, verified by reading the code paths and not flagged anywhere in the repo (details and fix in §4.1): **outbox `id` order is insert order, not commit order.** Two concurrent updates to the same `users` or `alert_rules` row can insert outbox rows in the opposite order to the order in which they commit (the row lock is taken at flush, *after* the outbox INSERT). The relay then publishes them in id order. Depending on relay timing, the last event a consumer sees may not reflect the last committed state. The window is small and requires concurrent writes to the *same* aggregate, but it is real, and it is exactly the class of race the deferred `version` column exists to close.

### 0.4 usage-service today [CURRENT]

- One `@KafkaListener` on `energy-usage-events`, group `usage-service`, single container thread, `auto-offset-reset=earliest`, `ErrorHandlingDeserializer` wrapping `JacksonJsonDeserializer`.
- Per record: `DeviceIdCache.isKnown(deviceId)` (an in-JVM `volatile Set<Long>` refreshed every 30 s by `GET /api/v1/devices/ids` on device-service; failed refresh keeps the last snapshot), then **one synchronous `influxDBClient.writePoint(point)` per record**, three `log.info` lines per record.
- InfluxDB schema: measurement `energy_readings`, tag `device_id`, fields `consumed_energy` (double), `device_known` (boolean), timestamp from the event. **No `user_id` tag.** Database `energy_usage`, auto-created on first write.
- **Unknown devices are never rejected** — the point is written with `device_known=false`. The uncommitted diff adds a comment asking whether it should be dropped; this review answers that in §1.4 / §3.2.
- Failures propagate to a `DefaultErrorHandler` with `FixedBackOff(1000 ms, 2 attempts)` → `DeadLetterPublishingRecoverer` → `energy-usage-events-dlt`. The backoff **blocks the single consumer thread**.
- `@EnableScheduling` is on, and Spring Boot's default `TaskScheduler` pool size is **1**. Today only `DeviceIdCache.refresh()` uses it. The moment an alert-evaluation `@Scheduled` job is added, both will share one thread (§2.2).
- No alert evaluation, no `ThresholdExceededEvent`, no outbound topic, no Postgres, no Redis.

### 0.5 Reading model [CURRENT vs decided]

The wire field is still `consumedEnergy` (a per-tick delta, `@Positive`). `../../../CLAUDE.md` records the decision to move to a **cumulative register** (`cumulativeEnergyKwh`, `@PositiveOrZero`) with per-device simulator state. Not implemented. This review evaluates alert evaluation against the *decided* cumulative model, because that is what will exist when evaluation is built, and it changes the query shape materially (§1.3).

### 0.6 Infrastructure facts that constrain the design

- **Kafka retention:** the compose file does not set `KAFKA_LOG_RETENTION_HOURS`; the broker default is 168 h (7 days) **[UNVERIFIED against the exact `apache/kafka:latest` you pulled — it is a floating tag]**. `cleanup.policy` is the default `delete`, not `compact`, on every topic. This is decisive for Section 5.
- **InfluxDB 3 Core query window:** the Core (free) edition restricts queries to roughly the most recent 72 hours of data; Enterprise lifts this **[UNVERIFIED — check the docs for the exact version the `3-core` tag resolves to]**. If true, every `EvaluationWindow` (max `ONE_DAY`) fits comfortably, but any future "monthly report" feature does not.
- **InfluxDB duplicate-point semantics:** writing a point with the same measurement + tag set + timestamp replaces the earlier one (last write wins). The repo's comment in `UsageService` relies on this for retry idempotency. Whether the replacement is per-field merge or whole-row replace in 3 Core is **[UNVERIFIED]**; do not build a "backfill `device_known` later" step on the assumption that a partial write merges (§3.2).

---

## Summary verdict

| Question | Answer |
|---|---|
| Is the **current** usage-service the right foundation? | Mostly yes. The advisory-cache, tag-don't-drop, DLQ-on-write-failure choices are correct. Its throughput ceiling (one synchronous write per record on one thread, one partition) is the first real limit and is fixable without redesign. |
| Is the **proposed** design feasible? | Partly. Scheduled evaluation over InfluxDB is feasible at this project's realistic scale *if* it is designed as "a few bulk as-of queries per tick," never "one query per rule." Replicating full user/device/rule state into a local Postgres **plus** Redis, and **rejecting** readings for unknown devices, are the wrong parts. |
| Biggest hidden problem? | The proposed local database is **not rebuildable** as designed: 7-day Kafka retention, no snapshot endpoints, and the outbox's `partitionKey = userId` makes topic compaction unusable (it would collapse every rule event of a user into one). Section 5. |
| Redis? | **Not recommended.** It adds a network hop to the hot path (slower than the in-JVM `Set` you already have), a new failure mode, and solves a problem (shared mutable cache across instances) that does not exist yet. |
| Stream processing? | Not for v1. Documented pivot criteria in §1.4. |
| Rejection of unknown-device readings? | **No.** Keep tag-and-write. Make evaluation resolve device→user in the application so unknown-at-ingest readings need no later repair (§3.2). |

---

## 1. Feasibility & the hard truths

### 1.1 Feasibility verdict

**[PROPOSED]** usage-service as a "multi-domain aggregation engine": consume `user-*`, `alert_rule-*`, `device-*` events into a local Postgres, mirror into Redis, validate each reading against Redis, write to InfluxDB, run a scheduled evaluation over InfluxDB, publish `ThresholdExceeded`.

**Verdict: the *ingestion* half is realistic and already mostly built; the *evaluation* half is realistic only under a specific query design; the *state-replication* half is over-scoped.** Concretely:

1. **Ingestion path.** Kafka → validate → InfluxDB write is a standard shape. The current implementation's ceiling is not the cache lookup (a `HashSet.contains` is nanoseconds) but the **one synchronous HTTP write per record on a single consumer thread**. Order-of-magnitude: a local InfluxDB write round trip is a few ms, so the current code tops out in the low hundreds to low thousands of points per second per partition, with the three `log.info` calls per record contributing measurably. This is fixable in place: batch listener (`spring.kafka.listener.type=batch`) + `writePoints(List<Point>)`, more partitions + `concurrency`, and per-record logging demoted to `debug`. No architectural change needed. **[RECOMMENDED]**

2. **Evaluation path.** "Thousands of active alert rules" is only a problem if evaluation is per-rule. Rules are bounded to 8 distinct windows and one scope, and the cumulative model makes the per-window computation an "as-of" subtraction. `../../../CLAUDE.md` already records the right design: **≈1 + (distinct windows in use) bulk queries per tick, each returning one row per device**, then join and group in the application. That cost is flat in the number of rules and linear in the number of *devices*. With 1 000 devices it is trivially cheap; with 100 000 devices it is ~9 result sets of 100 000 rows per tick, which InfluxDB 3's DataFusion engine handles but which starts to matter at a 1-minute cadence in JVM memory and Arrow deserialization. That is the realistic ceiling of the polling design, and it is far above this project's scale.

3. **State replication.** Replicating *users* (names, emails, addresses) into usage-service has no consumer inside usage-service. Evaluation needs `ruleId, userId, window, threshold, enabled` and `deviceId → userId`. Nothing else. See §1.2 and §3 for the minimal state.

### 1.2 Microservices best practices — does the plan follow them?

**What the repo already does well [CURRENT]:** one schema per service with no cross-schema FKs; events, not shared libraries, as the integration contract (independent event record copies, alias-based type mapping); transactional outbox instead of dual writes; explicit REST timeouts; per-consumer `group.id`; poison-message isolation via `ErrorHandlingDeserializer` + DLT.

**Where the proposal drifts [PROPOSED → critique]:**

- **Scope creep into a "multi-domain" service.** A microservice's boundary is its reason to change. usage-service's reason to change is "how readings are stored and how rules are evaluated." Mirroring users' names/emails/addresses makes it change whenever the user model changes, for no benefit. The correct home for "what do I need to know about the user to notify them" is the future alerting-service (which needs email, not thresholds). **[RECOMMENDED]** usage-service consumes `ALERT_RULE_*` and `USER_DELETED` (and, once they exist, `DEVICE_*`), and *ignores* `USER_CREATED`/`USER_UPDATED` entirely via the `event-type` header.
- **Rejection under eventual consistency is a coupling smell.** Making ingestion correctness depend on replication lag from two other services turns an availability property (readings always land) into a consistency property (readings land only if the replica is current). That is the wrong trade for telemetry. Detailed in §1.4 and §3.2.
- **Redis as a second cache layer for a single-instance service is premature infrastructure.** Best practice is to add a component when a measured constraint demands it. The in-JVM snapshot already gives sub-microsecond lookups. Redis would *add* ~0.2–1 ms per lookup on the hot path unless you re-introduce a local cache in front of it, at which point Redis is a cache of a cache.
- **"CQRS read model" is a slightly misleading label.** usage-service is not the query side of a command/query split of its own aggregate; it is an *event-carried state transfer* consumer of other services' aggregates, plus an owner of its own aggregate (readings, evaluation state). That framing matters for Section 5: a true CQRS read model is rebuildable by definition from its own event store; a state-transfer replica is only rebuildable if the *upstream* stream is retained or snapshot-able.
- **Shared physical Postgres instance.** Fine for a learning project; note that "usage-service gets its own schema in the same instance" preserves the logical boundary. Do not put usage-service tables in `user_service` or `device_service` schemas.

### 1.3 Downsides & performance traps

**Trap 1 — per-rule queries.** If evaluation is written as `for rule in rules: query(rule.user, rule.window)`, cost is O(rules × window) round trips per tick, each a Flight/gRPC call with Arrow decoding. At 5 000 rules and a 60 s tick that is ~80 queries/s continuously, with InfluxDB doing the same "last reading per device" scan 5 000 times. **Avoid.** Bulk as-of per window, join in memory.

**Trap 2 — `GROUP BY user_id` in InfluxDB requires `user_id` as a tag written at ingest.** `../../../CLAUDE.md`'s roadmap plans this. It has a hidden cost: tags are part of the series key. A reading written while the device→user mapping is unknown gets *no* `user_id` tag (or a placeholder); "fixing" it later means rewriting the point into a **different series**, and the old point stays. You end up with a reconciliation job that rewrites history. **[RECOMMENDED]** do **not** write `user_id` into InfluxDB. Keep `device_id` as the only tag; query as-of values *per device* (one row per device per boundary); map device→user and sum in usage-service using its local mapping. This makes the "unknown at ingest" problem disappear (§3.2) and keeps InfluxDB free of a second service's identity.

**Trap 3 — the scheduler thread is shared.** Spring Boot's `@Scheduled` pool defaults to one thread. A slow evaluation tick (InfluxDB query timeout is 30 s in `InfluxDBConfig`) will delay `DeviceIdCache.refresh()` and any other scheduled task, and a hung refresh (5 s read timeout) delays evaluation. Set `spring.task.scheduling.pool.size` ≥ 2 or give evaluation its own executor (§2.2).

**Trap 4 — blocking retry on the consumer thread.** Already documented in the repo's own comments. With the Influx write on the listener thread and `FixedBackOff` sleeping that thread, an InfluxDB outage of N minutes stalls the partition for N minutes *and then* dumps every record into the DLT once retries are exhausted. A total dependency outage should **pause consumption**, not dead-letter the stream (§2.1).

**Trap 5 — as-of queries are hard to index-hit without `time <= boundary` pushdown.** The planned `ROW_NUMBER() OVER (PARTITION BY device_id ORDER BY time DESC)` filtered to `time <= boundary` is correct SQL for DataFusion, but bound it: `WHERE time <= $boundary AND time >= $boundary - $maxStaleness`. Without the lower bound, "last reading as of now − 24 h" scans all history for devices that have gone silent. The max-staleness cap in the roadmap is not only a data-quality rule; it is what keeps the query bounded.

**Trap 6 — cumulative counter resets.** Deferred in `../../../CLAUDE.md`, but evaluation must at least *detect* `latest < earlier` and treat the delta as "unknown" (skip device this tick, emit a metric), never as a negative usage that masks a real threshold breach on the user's other devices.

**Trap 7 — InfluxDB 3 Core's 72-hour query window [UNVERIFIED]**. Fine for `ONE_DAY` windows; would break any longer window or any "last month" endpoint added later. Check before committing to 3 Core for anything beyond alerting.

**Trap 8 — duplicate `NewTopic` declarations.** Both ingestion-service and usage-service declare `energy-usage-events` with 1 partition. Spring's `KafkaAdmin` will *increase* partitions if a bean declares more than exist, and will not decrease. If one service is changed to 3 partitions and the other still says 1, whichever starts first wins and the other logs nothing useful. Pick one owner (ingestion-service, the producer) and have usage-service declare only its own DLT.

### 1.4 The stream-processing alternative

**When would you actually need it?** Only when one of these becomes true: required alert latency < the tick interval you can afford (sub-30 s), device count makes the per-tick bulk queries exceed the tick, or you want per-reading reactions (e.g. "device went silent"). None is true today.

**Kafka Streams design (if pivoting):**

1. `energy-usage-events` keyed by `deviceId` → `KStream<deviceId, reading>`.
2. Per-device state store: a bounded ring of `(timestamp, cumulative)` covering the longest window (24 h) at the device's reporting cadence. On each reading, compute usage for each of the 8 windows as `latest − asOf(now − window)` and emit `KStream<deviceId, WindowUsage[]>`.
3. `device-domain-events` (does not exist yet) → `GlobalKTable<deviceId, userId>`; join to re-key by `userId`.
4. Aggregate per `(userId, window)` over the user's devices into a `KTable`.
5. `user-domain-events` → `GlobalKTable<ruleId, rule>` (note: needs its own keying; the outbox key is `userId`, which is fine for a `GlobalKTable` only if the value carries `ruleId` and the processor maintains a map — or a separate compacted rules topic keyed by `ruleId`, see §5).
6. Join usage with rules, apply edge-trigger state in a store, emit `ThresholdExceeded` to a new topic.

| | Scheduled evaluation over InfluxDB **[RECOMMENDED for v1]** | Kafka Streams | Flink |
|---|---|---|---|
| Latency | tick interval (30–60 s) | per reading (sub-second) | per reading |
| Scale-out | vertical; one evaluator (needs leader election if >1 instance) | horizontal by partition (today: 1 partition) | horizontal, separate cluster |
| Needs device events as a Kafka stream | no (REST poll works) | **yes** (device-service outbox required) | yes |
| State | tiny Postgres table for firing state | RocksDB stores + changelog topics (auto-rebuildable) | checkpoints + savepoints |
| "No reading for N minutes" alerts | trivial (it's a query) | needs punctuators / wall-clock timers | native timers |
| Late / out-of-order readings | free — the query sees whatever is stored | must define grace periods; late readings may be dropped | native event-time + watermarks |
| Reboot/counter reset | detected in one place (the evaluator) | detected per device in the processor | same |
| Complexity for a learning project | low | medium-high (topology, serdes, stores, rebalances, exactly-once config) | high (another runtime) |
| InfluxDB still needed | yes (source of truth for readings) | only for history/dashboards | same |

**[RECOMMENDED]** Build evaluation as a pure function `evaluate(rules, deviceOwners, asOfReadingsNow, asOfReadingsWindowStart, firingState) → (alerts, newFiringState)` with no I/O inside. Scheduled evaluation calls it with InfluxDB results; a later Streams processor could call the same function per key. The pivot then changes the *plumbing*, not the *logic*.

---

## 2. Resilience & fault tolerance (Resilience4j)

Resilience4j is **not** on any classpath today. Adding `io.github.resilience4j:resilience4j-spring-boot3` (check the artifact that supports Spring Boot 4.x at the time you add it — the `spring-boot3` module line may or may not be compatible with Boot 4.1; verify) plus AOP gives `@CircuitBreaker`, `@Bulkhead`, `@Retry`, `@TimeLimiter`, `@RateLimiter`. Where each belongs:

### 2.1 Circuit breakers

**Kafka is already the buffer. Do not add a secondary buffer; use backpressure.**

| Protected call | Breaker config (starting point) | Fallback when OPEN |
|---|---|---|
| InfluxDB **write** (listener hot path) | `slidingWindowType=COUNT_BASED, slidingWindowSize=50, failureRateThreshold=50, slowCallDurationThreshold=2s, slowCallRateThreshold=50, waitDurationInOpenState=15s, permittedNumberOfCallsInHalfOpenState=5`; record `IOException`, timeout, InfluxDB client exceptions; **ignore** deserialization exceptions | **Pause the listener container** (`KafkaListenerEndpointRegistry.getListenerContainer(id).pause()`), register an `EventConsumer` on the breaker's state transitions so `HALF_OPEN`/`CLOSED` calls `resume()`. While paused the consumer keeps polling (no rebalance) but delivers nothing; Kafka holds the backlog. **Do not** route the stream to the DLT while the breaker is open — the DLT is for records that *cannot* be processed, not for records that *cannot be processed right now*. |
| InfluxDB **query** (evaluation tick) | separate breaker instance (different failure profile and protocol — Flight/gRPC vs HTTP write): `slowCallDurationThreshold=10s`, `waitDurationInOpenState=60s` | Skip the tick, increment a `usage.evaluation.skipped` counter, log once per state change. Firing state is untouched, so no spurious alerts. Never fall back to "evaluate on partial data." |
| device-service REST (`DeviceIdCache.refresh`) | `waitDurationInOpenState=60s`, small window | Already handled — keep the previous snapshot. The breaker only saves the 3 s + 5 s timeouts every 30 s while device-service is down. Optional. |
| usage-service local Postgres (event consumer, firing state) | breaker mostly redundant with the connection pool's own timeouts; use **Retry** (§2.3) | On persistent failure the record throws → container `DefaultErrorHandler` retries in place → DLT. For *domain events* this is correct: a domain event that cannot be applied should be dead-lettered and alerted on, not skipped (ordering). |
| Kafka producer for `ThresholdExceeded` | none — the producer client already retries; use the **outbox pattern** in usage-service's Postgres so evaluation state and the alert event commit atomically (§4.1) | n/a |

**Why "pause" beats "retry topic" for the reading stream:** readings are keyed by `deviceId` and idempotent in InfluxDB (same tags + time → replace), so *reordering* a reading is harmless and a non-blocking retry topic (`@RetryableTopic`) is *safe* here. But it is not *useful* for a total outage: it copies the whole stream through the retry topics and then to the DLT anyway. Reserve retry topics for *isolated* failures (one malformed point among many), and pause for outages. In practice, with a breaker in front, isolated failures fall through to the existing `DefaultErrorHandler` path, which is already correct.

### 2.2 Bulkheads

The premise in the prompt ("CPU-intensive alert evaluation loops") is not accurate for the recommended design: evaluation is **I/O-bound** (a handful of InfluxDB queries and a join over a few thousand rows). The starvation risk is **thread contention**, not CPU:

1. **Kafka listener threads** — owned by the listener container (`concurrency` = number of partitions). Never run anything else on them. Never block them on the scheduler.
2. **Scheduler threads** — Boot default pool is **1**. Set `spring.task.scheduling.pool.size=3` (evaluation, cache refresh, headroom) **or** run evaluation via `@Async` on a dedicated `ThreadPoolTaskExecutor` bean named e.g. `evaluationExecutor` and keep the scheduler for cheap triggers only. Also `@Scheduled(fixedDelay)` (not `fixedRate`) so a slow tick cannot overlap itself.
3. **Resilience4j `Bulkhead` (semaphore) on the InfluxDB *query* path**, `maxConcurrentCalls=2`, so a manual/admin query or an overlapping tick cannot saturate the Flight channel that the listener's writes share a process with. Writes use HTTP and do not need a semaphore; the listener concurrency already bounds them.
4. **`ThreadPoolBulkhead` for the evaluation job**, `coreThreadPoolSize=1, maxThreadPoolSize=1, queueCapacity=0`, if using the Resilience4j abstraction instead of a plain executor. Either is fine; do not do both.
5. **Separate InfluxDB client instances are not required**; separate *timeouts* are already configured (`writeTimeout=10s`, `queryTimeout=30s`). Consider lowering `queryTimeout` to be below the tick interval so a stuck query cannot outlive its tick.
6. Tomcat threads (usage-service has `webmvc` for a future admin/health API) are already isolated from both.

### 2.3 Retries vs rate limiters

| Situation | Retry? | Notes |
|---|---|---|
| Transient Postgres lock/deadlock while applying a domain event (`CannotAcquireLockException`, `TransientDataAccessException`, serialization failure) | **Yes** — `@Retry(maxAttempts=3, waitDuration=200ms, exponentialBackoff, randomized)` on the *apply* method, retrying **only** those exception types | Cheap, in place, preserves ordering. On exhaustion, throw → container retry → DLT. |
| InfluxDB write in the listener | **No application-level retry.** The container's `DefaultErrorHandler` is the single retry mechanism; stacking `@Retry` on top multiplies attempts (2 × 3 = 6) and the thread block. | Improve the existing handler instead: `ExponentialBackOffWithMaxRetries`, and `addNotRetryableExceptions(IllegalArgumentException.class, …)` for permanently invalid points. |
| InfluxDB query in evaluation | **One** retry with short backoff at most; otherwise skip the tick | The next tick *is* the retry. |
| device-service REST poll | **No** | Next scheduled poll is the retry. |
| Publishing `ThresholdExceeded` | **No** in-process retry — use the outbox (§4.1), which retries by design | |
| **Rate limiter** on ingestion | **No** | Kafka *is* the rate control (consumer pulls at its own pace). A `RateLimiter` on the listener would just move the backlog into the broker, which is where it already is. |
| Rate limiter on alerts | **Not a rate limiter — edge triggering** | Fire on the under→over *transition* per rule, keep `last_fired_at`, and optionally a per-rule cooldown. A generic rate limiter would drop *other users'* alerts when one user's rules are noisy. |
| Rate limiter on DLT publishing | Optional | If the DLT is ever fed by an outage (which §2.1 prevents), a limiter would only delay the inevitable. Not needed with pause-on-open. |

---

## 3. Distributed-systems edge cases

### 3.1 Out-of-order events & idempotency

**What ordering you actually get [CURRENT]:**

- Within `user-domain-events`, all events for one user (user *and* their rules) share a partition key, so they arrive in the order the relay published them, which is outbox `id` order. §0.3 explains why that is *insert* order, not *commit* order, for concurrent writes to the same aggregate; fix in §4.1. Absent that race, order is correct.
- Across topics (`user-domain-events` vs a future `device-domain-events`) there is **no ordering guarantee at all**. `USER_DELETED` may arrive before or after the `DEVICE_DELETED` events device-service will emit when it reacts to that same deletion.
- Redelivery (at-least-once) produces duplicates but never reorders within a partition: Kafka redelivers from the last committed offset, so a duplicate is always followed by everything that came after it, again, in order.

**What "delete before create" really means here:** for a *single key on a single topic* it cannot happen via the outbox. It can only appear as (a) a redelivered *old* `CREATED` after a `DELETED` was already applied — a duplicate, not a reorder — or (b) cross-topic, e.g. `DEVICE_CREATED` for user 7 arriving after `USER_DELETED` for user 7 (device-service's REST existence check races with the deletion).

**[RECOMMENDED] consumer rules for usage-service's read model:**

1. **Idempotent by construction.** `ALERT_RULE_CREATED`/`UPDATED` → `INSERT … ON CONFLICT (rule_id) DO UPDATE`. `ALERT_RULE_DELETED` → `DELETE WHERE rule_id = ?`. `USER_DELETED` → `DELETE FROM alert_rules_snapshot WHERE user_id = ?` and `DELETE FROM device_owners WHERE user_id = ?`. `DEVICE_CREATED`/`UPDATED` → upsert `(device_id, user_id)`. `DEVICE_DELETED` → delete by `device_id`. Every one of these is safe to apply twice.
2. **Stale-event guard per row.** Store `last_applied_outbox_event_id` (from the `outbox-event-id` header) on each snapshot row and skip an event whose id is lower than the stored one. This is correct *per producer* because outbox ids are monotonic within user-service's database, and it neutralises case (a) above. Caveat: it only orders events from the *same* producer; device events carry their own outbox ids from a different sequence. Never compare ids across producers. Once upstream adds a per-aggregate `version` (§4.1), prefer that.
3. **Tombstones with a grace period** for deletes: keep `deleted_users(user_id, deleted_at)` for, say, 7 days (≥ Kafka retention). A `DEVICE_CREATED`/`ALERT_RULE_CREATED` for a user in that table is applied *and immediately marked orphaned* (or simply ignored). A periodic sweep removes tombstones older than the grace period. This handles case (b).
4. **Orphans are harmless to evaluation.** A `device_owners` row pointing at a deleted user is never consulted, because evaluation iterates *rules* (deleted with the user) and looks up devices *for those rules' users*. Orphans are a hygiene issue, not a correctness one; the sweep in (3) covers them.
5. **Dispatch on headers.** Read `event-type`/`aggregate-type` first; ignore `USER_CREATED`/`USER_UPDATED` without parsing the payload. Use `spring.kafka.consumer.value-deserializer=StringDeserializer` (wrapped in `ErrorHandlingDeserializer`) for this topic — there is no `__TypeId__` on outbox messages, so `JacksonJsonDeserializer` would fail. Parse with Jackson 3 into usage-service's own payload records (the same "independent copy" rule as `EnergyUsageEvent`).
6. **Separate `@KafkaListener` container per topic**, each with its own `group.id`-scoped error handler and DLT (§4.3). One container per topic also means a poison domain event does not stall reading ingestion.

### 3.2 Ingestion race conditions

**The question "what if telemetry arrives at the exact millisecond the device is created/deleted" has a simple answer once validation is not a gate:** nothing special happens.

- **Reading before `DEVICE_CREATED` reaches usage-service:** the point is written with `device_id` and no owner knowledge. When the mapping later exists, the *next evaluation tick* queries by `device_id` and finds the reading. **No reconciliation job needed** — provided `user_id` is **not** an InfluxDB tag (§1.3 Trap 2). This is the single strongest argument for resolving device→user in the application rather than in InfluxDB.
- **Reading after `DEVICE_DELETED`:** the point is written; no rule's user maps to the device; evaluation ignores it; InfluxDB retention eventually removes it. If the device is later re-created *with the same id* (impossible with `BIGSERIAL`, which never reuses ids), old readings would reappear; with the current schema that cannot happen.
- **Genuinely bogus device ids** (the simulator deliberately sends some): stored, tagged, ignored. Add a `usage.readings.unknown_device` counter and an InfluxDB database retention period so they age out. The real fix is upstream: once devices authenticate to ingestion-service (a device token/`keycloak_id` phase), "unknown device" becomes a 401 at the edge, and the `device_known` question disappears from usage-service. The comment added in the working tree ("Is it even relevant after auth check?") is asking exactly the right question; the answer is: **keep tagging until auth exists, then remove the field.**

**Is rejection ever right?** Only for *malformed* readings (negative cumulative, timestamp far in the future, missing device id) — and those should be rejected at ingestion-service's `@Valid` boundary with a 400, never inside usage-service where the caller is long gone. Rejecting *valid-looking* readings because a replica is a few seconds behind converts a lag into permanent data loss for a device that, in the overwhelming majority of cases, really exists.

**What about the `device_known` field itself?** It is informational. Do not plan to "flip it to true later" — that relies on partial-point-update semantics that are [UNVERIFIED] for InfluxDB 3 Core and would double write volume. If you want an audit of readings that arrived before their device was known, a query joining `device_known=false` points against the current mapping answers it at read time.

### 3.3 Redis sync strategy

**[RECOMMENDED] Do not introduce Redis.** The recommended state is small enough to hold in the JVM (a `Map<Long,Long>` of device→user and a `Map<Long,Rule>` for a few thousand rules is kilobytes to low megabytes) and is refreshed from usage-service's own Postgres tables, which are in turn fed by the event consumers. Concretely:

- The event consumer writes to Postgres (durable, rebuildable).
- An in-JVM snapshot (`volatile` immutable maps, exactly like today's `DeviceIdCache`) is rebuilt from Postgres on startup and either (a) on each applied event (update the map after the transaction commits — `TransactionSynchronization.afterCommit`) or (b) on a short timer. (a) is preferable: it is event-driven and has no lag beyond Kafka's.
- The listener hot path reads the map. Zero network hops.

**If you nevertheless add Redis** (the only legitimate reason: several usage-service instances that must share *mutable* firing state, or a cache far larger than heap), then:

- Postgres is the system of record inside usage-service; Redis is derived. **Write-through from the event consumer, after commit** (`afterCommit` hook), keyed `device:{id} → userId` and `rule:{id} → json`. If the Redis write fails, log and rely on the next rebuild — never fail the Kafka record over a cache miss, and never make the *cache* write part of the DB transaction.
- On a cache miss in the hot path: **do not** fall back to a synchronous Postgres read per reading (that reintroduces the very latency Redis was supposed to remove). Treat a miss as "unknown", tag, and continue.
- Cache-aside with TTL is the wrong fit here because the read path must never block on a DB; write-through with periodic full rebuild (e.g. every 5 min, `SCAN`-free by writing a versioned hash and swapping a pointer key) is the pattern that minimises inconsistency under heavy deletes.
- Firing state must **not** live only in Redis (§5).

---

## 4. Upstream ecosystem requirements

### 4.1 user-service and device-service

**user-service outbox — does it work for the proposed usage-service consumer? [CURRENT: mostly yes, with two gaps]**

What is already right: atomic outbox write; `partitionKey = userId` for both aggregates so a user's rules and the user's deletion are ordered together; headers for cheap filtering; `outbox-event-id` for dedup; at-least-once with confirmed acks; batch stops on first failure. **Exactly-once is not needed** for any consumer identified so far — every projected operation is an upsert or a delete keyed by the aggregate id, which is naturally idempotent. At-least-once + idempotent consumers is the right target; do not add Kafka transactions or `read_committed` isolation for this.

Gap A — **commit-order vs id-order race (§0.3).** [RECOMMENDED] Add the deferred `version BIGINT` column with `@Version` to `users` and `alert_rules`. Concurrent updates then fail fast with an optimistic-lock exception instead of interleaving, the failed transaction rolls back its outbox row, and id order becomes commit order for a given aggregate. Include `version` in `UserChangedPayload` and `AlertRuleChangedPayload` so consumers can also apply "ignore if `version` ≤ stored". This is a forward migration `V5` and is additive.

Gap B — **no `ALERT_RULE_DELETED` for cascaded rules.** Documented and acceptable *for consumers that key rules by user* (usage-service can `DELETE WHERE user_id = ?`). It is *not* acceptable for any consumer that keys rules by `ruleId` alone without also tracking `userId` — the snapshot table must carry `user_id`. Document this obligation in the consumer contract.

Also recommended for the contract, all additive:

- A `schema-version` header (or `schemaVersion` field) starting at `1`, so payload evolution can be detected by consumers before parsing.
- Keep `occurredAt` but document that it is *not* an ordering key.
- A `source` header (`user-service`) — trivial now, useful the moment a second producer exists.

**device-service — what must be built [PROPOSED requires it; nothing exists]:**

1. **Transactional outbox, same shape as user-service** (`outbox_events` in `device_service` schema, `aggregate_type = DEVICE`, event types `DEVICE_CREATED`/`DEVICE_UPDATED`/`DEVICE_DELETED`, payload `{deviceId, userId, deviceName, deviceType, location, version, occurredAt}` / `{deviceId, userId, occurredAt}`), topic `device-domain-events`. Extract-and-share is tempting; the project's rule is one copy per service, and the outbox is small enough that copying is fine. Reuse the *names* exactly (`OutboxService`, `OutboxRelay`, `OutboxEvent`, `@SkipLogging`).
2. **Partition key = `userId`**, not `deviceId`. Ownership is immutable (`DeviceOwnerImmutableException`), so the key never changes for a device's lifetime, and keying by owner keeps "all of user 7's device events" ordered relative to each other, which matters when device-service deletes a user's devices in bulk. It cannot, however, order them relative to `USER_DELETED` on the *other* topic — that is inherent and handled by the tombstone rule in §3.1.
3. **Consume `user-domain-events` (`USER_DELETED`)** with its own `group.id=device-service`, delete the user's devices in a transaction *that also writes `DEVICE_DELETED` outbox rows*, so downstream learns about each device's removal. Idempotent (`DELETE WHERE user_id = ?` on redelivery deletes nothing and emits nothing).
4. **Audit columns and `version`** on `devices` (`V2`), matching `users`/`alert_rules`.
5. **A bulk snapshot endpoint** for bootstrap and rebuild (§5): `GET /api/v1/devices/owners` returning `[{deviceId, userId}]` (or extend the existing `/ids` endpoint — but rename it; `/ids` will no longer describe what it returns). Paginate if the fleet is large. Likewise user-service needs `GET /api/v1/alert-rules?enabled=true` across all users (today rules are only reachable nested under a user).

**Kafka Streams note:** if the streaming pivot ever happens, it wants `device-domain-events` compacted and keyed by `deviceId`. That conflicts with "key by `userId`". The clean resolution is the *state topic* pattern in §5: the domain-event topic stays keyed by owner for ordering; a separate compacted `device-owners` topic keyed by `deviceId` is produced by device-service for anyone who wants "current state, latest wins."

### 4.2 ingestion-service

Everything below is compatible with the decided cumulative-counter change and should land with it:

- **Rename to `cumulativeEnergyKwh`, `@PositiveOrZero`**, both event copies and the InfluxDB field, as already decided. Consider a `readingId` (UUID from the device, or `hash(deviceId, timestamp)`) only if devices can legitimately send two different values for the same timestamp; otherwise `(device_id, time)` is already the identity.
- **Keep keying by `deviceId`** so a device's readings stay ordered. Raise partitions (e.g. 6) once usage-service moves to `concurrency > 1`; make ingestion-service the *only* declarer of the topic.
- **Fire-and-forget is not free of blocking.** `KafkaTemplate.send()` blocks on metadata fetch up to `max.block.ms` (default 60 s) when the broker is unreachable, so an HTTP thread can hang despite the `whenComplete` design. Set `spring.kafka.producer.properties.max.block.ms` to something like 2 s and return 503 on that failure. Also pin `acks=all` and `enable.idempotence=true` explicitly, as user-service does.
- **No device validation at ingest** (the existing comment is right) *until* device authentication exists. When it does, validation is "is this token valid," answered locally from a JWT, not "does this id exist in device-service."
- **Do not** add a REST call or a cache lookup to ingestion-service for usage-service's benefit; the mapping belongs where it is used (evaluation).
- Simulator: per-device running totals, and deliberately include a periodic "reboot" (counter reset to 0) for a small fraction of devices so the deferred reset-detection problem has test data when it is picked up.

### 4.3 Dead-letter and retry topology

**[CURRENT]** one DLT, `energy-usage-events-dlt`, produced by usage-service via the default resolver (`<topic>-dlt`).

**Problem with the default resolver once a second consumer exists:** device-service and usage-service will both consume `user-domain-events`; both would default to `user-domain-events-dlt`, so a poison record dead-lettered by device-service lands in a topic usage-service might also be draining, and the `DLT_*` headers are the only way to tell whose failure it was.

**[RECOMMENDED] topology** — DLTs are **per consumer group**, never per topic:

| Source topic | Consumer group | Retry | DLT |
|---|---|---|---|
| `energy-usage-events` | `usage-service` | in-place blocking backoff (existing), breaker-pause on outage | `energy-usage-events.usage-service.dlt` |
| `user-domain-events` | `usage-service` | in-place blocking backoff **only** (never retry topics — they reorder keyed events) | `user-domain-events.usage-service.dlt` |
| `user-domain-events` | `device-service` | same | `user-domain-events.device-service.dlt` |
| `device-domain-events` | `usage-service` | same | `device-domain-events.usage-service.dlt` |
| `threshold-exceeded-events` (new, produced by usage-service via outbox) | `alerting-service` | same | `threshold-exceeded-events.alerting-service.dlt` |

Implementation: pass a custom `BiFunction<ConsumerRecord<?,?>, Exception, TopicPartition>` to `DeadLetterPublishingRecoverer` that appends `"." + groupId + ".dlt"`. Each consuming service declares its *own* DLTs as `NewTopic` beans (it is the producer of them). Longer retention on DLTs than on source topics (they are the audit trail). Every DLT needs an owner: a metric on lag/size (needs Actuator + Micrometer, neither present) and a documented reprocessing procedure — for domain events, reprocessing must replay *in order after the fix*, which usually means "fix the consumer, reset the group's offset to the failed record" rather than "consume the DLT."

**Retry topics** (`@RetryableTopic`, non-blocking) are acceptable *only* for `energy-usage-events` (idempotent, order-insensitive writes) and are not recommended even there in v1 (§2.1).

---

## 5. Recovery and rebuildability

### 5.1 Inventory of usage-service state under the proposed design

| State | Where (proposed) | Source of truth | Rebuildable from Kafka? | Rebuildable from upstream REST? |
|---|---|---|---|---|
| Alert rules snapshot | Postgres + Redis | user-service | **Only within retention (≈7 days by default)** — and see 5.2 | Not today: no cross-user rules endpoint |
| Device→user mapping | Postgres + Redis | device-service | No device events exist today; once they do, same 7-day caveat | Partially: `/ids` gives ids, not owners; a per-device `GET` loop is O(devices) calls |
| User profile mirror (proposed) | Postgres | user-service | same 7-day caveat | `GET /api/v1/users` exists (unpaginated) |
| Per-rule firing/edge state (`last_fired_at`, `currently_over`) | proposed: unclear (prompt implies Redis) | **usage-service itself** — nothing upstream has it | **No.** It is derived from readings *and* prior evaluations; replaying readings would not reproduce historical alert timestamps | No |
| Readings | InfluxDB | ingestion-service via Kafka | Only the last ≈7 days | No |
| Kafka consumer offsets | `__consumer_offsets` | Kafka | n/a | n/a |

### 5.2 Why the proposed design accidentally creates a non-rebuildable database

Three independent facts combine:

1. **Retention is time-based deletion, ≈7 days, on every topic** (compose does not override `log.retention.hours`; `cleanup.policy` is default `delete`). A rule created 8 days ago whose events have been deleted from the log cannot be recovered by replaying `user-domain-events` from `earliest`.
2. **Compaction cannot be switched on as a fix.** Log compaction keeps the *latest record per key*. The outbox key is `userId` for *every* event of that user, so compaction would keep exactly one event per user — the most recent one — and discard every `ALERT_RULE_CREATED` for that user's other rules. The ordering guarantee and compaction are, with this key, mutually exclusive. This is a verified consequence of `OutboxEvent.partitionKey`'s design, not a hypothetical.
3. **No snapshot endpoints exist** for the two things usage-service needs (all enabled rules; all device owners).

Therefore, if usage-service's Postgres (and Redis) are lost more than 7 days after any relevant event, **the rules and mapping cannot be reconstructed from anything the system currently exposes.** The service would come back up with an empty read model and silently evaluate nothing. That is the definition of an accidental non-rebuildable database.

### 5.3 [RECOMMENDED] making it rebuildable

Pick **one** of the two standard remedies; both are additive.

**Option A — snapshot + replay (simplest, fits the REST-first style of this repo):**

1. user-service: `GET /api/v1/alert-rules?enabled=true&page=…` (all users). device-service: `GET /api/v1/devices/owners?page=…`.
2. usage-service on startup with an empty read model (or on an explicit `rebuild` admin command): record `T₀ = now()`; pull both snapshots; write them to Postgres; then **reset its consumer group offsets to `T₀ − safety margin`** (e.g. 10 minutes; `KafkaAdmin`/`AdminClient.alterConsumerGroupOffsets` by timestamp) and start consuming. Events between the snapshot and the reset point are applied twice — harmless, they are idempotent — and nothing is missed.
3. Firing state: accept loss. Document the consequence precisely: after a rebuild, every rule is "not currently over"; the first evaluation may fire an alert that was already sent before the loss (one duplicate per rule, at most), and no alert is *missed*. That is the correct failure direction for an alerting system.

**Option B — compacted state topics (the streaming-friendly remedy):**

1. user-service additionally produces `alert-rules-state`, compacted, key `ruleId`, value = latest rule JSON or **null tombstone** on delete (including cascaded deletes, which the outbox writer must then emit explicitly — this is extra work compared to today). device-service produces `device-owners-state`, compacted, key `deviceId`.
2. usage-service rebuilds by reading each state topic from `earliest` to the current end offset; retention is infinite by construction.
3. The ordered domain-event topics stay exactly as they are for consumers that need *sequence* (device-service reacting to `USER_DELETED`).

Option A is recommended for this project's stage; Option B becomes worthwhile if the Kafka Streams pivot happens.

**Independently of A/B:**

- **Firing state lives in usage-service's Postgres, in the same transaction as the `ThresholdExceeded` outbox row.** That gives usage-service its own outbox (copy the pattern a third time; the repo's naming conventions make this mechanical) and makes "alert emitted" and "rule marked as fired" atomic. Redis is an acceptable *cache* of this, never its only home.
- **Readings:** InfluxDB is the only durable copy beyond Kafka retention. If readings matter beyond alerting, InfluxDB needs backups (object-store mode in 3.x makes this a bucket snapshot) — this is outside usage-service's rebuildability but inside the system's. If InfluxDB is lost, resetting the `usage-service` group offset to `earliest` re-ingests the last ≈7 days; the cumulative model means evaluation is correct again as soon as one reading per device has landed, which is the "self-healing" property `../../../CLAUDE.md` notes.
- **DLTs**: raise their retention (e.g. 30 days) so a failure is not silently aged out before anyone looks.
- **Document the retention number** in `docker-compose.yml` explicitly rather than inheriting a floating-tag default, and decide it deliberately (7 days is fine *if* Option A exists; without A or B it is the rebuild deadline).

---

## Recommended target architecture (consolidated)

**usage-service owns:**

- Postgres schema `usage_service` (Flyway, `V1`): `alert_rule_snapshot(rule_id PK, user_id, evaluation_window, threshold_kwh, enabled, last_applied_outbox_event_id, updated_at)`; `device_owner(device_id PK, user_id, last_applied_outbox_event_id)`; `deleted_user_tombstone(user_id PK, deleted_at)`; `alert_rule_state(rule_id PK, currently_over BOOL, last_evaluated_at, last_fired_at, last_usage_kwh)`; `outbox_events` (same shape as user-service's).
- In-JVM immutable snapshots of the first two tables, swapped after commit.
- InfluxDB `energy_readings` with **`device_id` as the only tag**, field `cumulative_energy_kwh` (+ `device_known` until device auth exists).
- Three listener containers: `energy-usage-events` (batch mode, `writePoints`, breaker-pause on outage), `user-domain-events` (filters to `ALERT_RULE_*` + `USER_DELETED`), `device-domain-events` (once it exists; until then keep the REST poll, but poll *owners*, not ids).
- One `@Scheduled(fixedDelay)` evaluation on a dedicated executor: per tick, `now` once; one as-of query at `now` and one per distinct window in use, all per device with a staleness lower bound; join with snapshots in memory; pure `evaluate(...)`; write `alert_rule_state` + `ThresholdExceeded` outbox rows in one transaction; relay to `threshold-exceeded-events` keyed by `userId`.
- Its own per-group DLTs, Resilience4j breakers on the two InfluxDB paths, `@Retry` on the Postgres apply path only, scheduler pool ≥ 2.

**usage-service explicitly does not:** mirror user profiles; use Redis; reject readings; write `user_id` into InfluxDB; query per rule; consume `USER_CREATED`/`USER_UPDATED`.

**Upstream obligations, in build order:** (1) user-service `V5` `version` columns + payload field; (2) device-service outbox + `device-domain-events` + `USER_DELETED` consumer + owners snapshot endpoint; (3) user-service cross-user enabled-rules endpoint; (4) ingestion-service cumulative rename + `max.block.ms`; (5) usage-service as above; (6) alerting-service consumes `threshold-exceeded-events` and is the only place that needs user email.

**Deviations from `../../../CLAUDE.md`'s roadmap that this review recommends and that should be reconciled there if accepted:** dropping the plan to write `user_id` as an InfluxDB tag and to `GROUP BY user_id` in InfluxDB (replaced by per-device as-of rows joined in usage-service); expanding `DeviceIdCache` into an owner map fed by a new `/owners` endpoint rather than `/ids`; adding per-consumer-group DLT naming; adding `version` to users/alert_rules now rather than later; explicitly rejecting Redis. Per the project's maintenance rule, `../../../CLAUDE.md` should be updated in the same change that adopts any of these — this review deliberately did not edit it.

---

## Appendix — items the prompt assumed that the repository contradicts

| Prompt premise | Repository reality |
|---|---|
| usage-service syncs events into "a local PostgreSQL instance" | usage-service has no datasource, no JPA, no Flyway |
| usage-service "caches this relational data in Redis" | No Redis container, client, or config anywhere |
| usage-service "validates device existence against Redis before streaming data into InfluxDB" | Validates against an in-JVM `Set` refreshed by REST every 30 s; **tags**, never rejects |
| "consumes `device-*` related events" | No device events exist; device-service has no Kafka dependency |
| Resilience4j "must be configured within usage-service" | Not a dependency of any service |
| "CPU-intensive alert evaluation loops" | Evaluation as designed is I/O-bound; the real contention is a single scheduler thread |
| "thousands of active alert rules" queried "constantly" | Rules are bounded to 8 windows × 1 scope; the query count is per window, not per rule |
| Exactly-once may be needed | Every projected operation is an idempotent upsert/delete; at-least-once is sufficient and already what the outbox provides |
