# user-service: what exists

The active service (port 8081, database `user_service` on its own Postgres container). This document describes what is built today. The outbox and relay are explained in `docs/design/01-outbox-design.md`, the account-deletion saga in `docs/design/03-account-deletion-saga.md`; here they get a pointer only.

## Package layout

Organised by feature, not by layer; main and test sources use the same package names. Dependencies point toward `user`, `outbox`, `kafka`, `consumer` and `keycloak` (which depend on nothing of ours; the Kafka templates are injected by bean name) and there are no cycles; `deletion.ack` depends on `deletion` and `consumer`, and nothing in `deletion` depends on `deletion.ack`; `provisioning` reaches Keycloak only through `deletion.OrphanEmailService`. `AccountDeletedException` (410) sits in `user` next to `UserStatus.isGone()`, because every feature throws it; `DeletionScheduledException` (409, the grace period is read-only) stays in `deletion`, so `profile` still depends on that package for it.

| Package | Contents |
|---|---|
| `user` | the core every feature uses: `User`, `UserRepository`, `UserStatus`, `UserResponse`, `UserNotFoundException`, `AccountDeletedException` |
| `profile` | the customer's own profile: `UserProfileController` (`GET`/`PATCH /api/v1/users/me`), `UserProfileService`, `UpdateUserRequest`, `@ValidTimezone` + `TimezoneValidator` |
| `provisioning` | row creation on the first request and the status gate: `UserProvisioningService`, `UserProvisioningInterceptor`, `WebMvcConfig` (registers the interceptor), `EmailNotVerifiedException`, `MissingIdentityClaimException`, `EmailBeingReleasedException` (503), `EmailAlreadyInUseException` (409) |
| `deletion` | the saga: `AccountDeletionController` (`DELETE /api/v1/users/me`, `POST .../deletion/cancel`), `DeletionSchedulingService`, `DeletionPromotionService` + `DeletionPromotionJob`, `KeycloakDeletionService` + `KeycloakDeletionJob`, `DeletionFinalizationService` + `DeletionFinalizationJob`, `OrphanEmailService`, `DeletionMetrics` (the watchdog gauges), `UserDeletionParticipant` (+ id, repository), `DeletionScheduledException`, `DeletionDeadlinePassedException`; `deletion.ack` holds the consumer of the participants' confirmations: `ParticipantAckListener`, `ParticipantAckService`, the registry `ParticipantAcks` with `ParticipantAck` and `ParticipantAcksConfig`, `RequiredParticipantsValidator`, `ParticipantAckMetrics` |
| `keycloak` | Keycloak's admin API as user-service uses it: `KeycloakAdmin` (the interface: `deleteUser`, `userExists`), `KeycloakAdminRestClient`, `KeycloakAdminConfig` (settings, circuit breaker, retry, the `keycloak.admin.circuit.state` gauge), `KeycloakUnavailableException`, `KeycloakCircuitOpenException` |
| `admin` | `AdminUserController`, `AdminUserService`, `AdminUserResponse`, `DeletionParticipantResponse` |
| `consumer` | what every Kafka consumer in the service shares: `EventEnvelopeReader` + `ReceivedEvent`, `PoisonEventException`, `ProcessedEvents` (the `processed_events` claim and purge) + `ProcessedEventsPurgeJob`, `KafkaConsumerConfig` (the error handler: dead-letter topic and retry policy) |
| `kafka` | the service's two Kafka producers, `KafkaProducerConfig`: `relayKafkaTemplate` (outbox relay) and `deadLetterKafkaTemplate` (dead-letter topics), each over a `ProducerFactory` bean of its own (the two differ only in the client id), and the silent `ProducerListener` both carry |
| `outbox` | the writer: `OutboxService`, `OutboxEvent`, `OutboxRepository`; `outbox.relay` is the reader: `OutboxRelay`, `OutboxBatchPublisher`, `RelayBatchResult`, `OutboxPurgeJob`, `OutboxMetrics` |
| `security` | resource-server configuration: `SecurityConfig`, the two problem-detail handlers, `AudienceValidator`, `KeycloakRealmRoleConverter`, `TokenVerificationUnavailableFilter` |
| `web` | `GlobalExceptionHandler` |
| (tests only) | `schema` (migrations and constraints), `keycloak` (the realm), `contract` (event fixtures) cut across features; `testsupport/containers`, `/keycloak`, `/jwt`, `/data` hold the helpers |

## Schema

Flyway owns all DDL (`ddl-auto=validate`). Each column is explained in its migration's comments.

| Migration | What it does |
|---|---|
| V1–V4 | history: the first users table, alerting fields (later dropped), `alert_rules`, an earlier outbox table |
| V5 | reset around `users.id = sub` (UUID, never generated); `status` `ACTIVE`/`DELETING`/`DELETED`; `deletion_requested_at`, `deleted_at`, `version`; checks `chk_users_status`, `chk_users_email`, `chk_users_deleted_consistency`; partial unique `uq_users_email` (`lower(email)`, non-`DELETED`); `idx_users_deleting` |
| V6 | `updated_at` trigger (the database stamps it) |
| V7, V8 | `outbox_events` and its parking column and payload-size check (see the outbox design) |
| V9 | `users.keycloak_deleted_at` and the saga consistency checks |
| V10 | `processed_events` (see the processed-events design; used by the deletion saga's confirmation consumer) |
| V11–V14 | saga schema, one concern per file: `deletion_scheduled_for`, `deletion_started_at`, the `DELETION_SCHEDULED` status and its checks and indexes (V11); `keycloak_deleted_at` allowed while `DELETING` (V12); `user_deletion_participants` (V13); the dropped `keycloak_disabled_at` and `devices_deleted` (V14). Described in the saga design, section 5 |

The database owns time: `created_at` has a default and a `BEFORE UPDATE` trigger stamps `updated_at`; both are mapped `@Generated` (`insertable/updatable = false`). The trigger leaves `version` alone, so native `UPDATE`s bump `version = version + 1` themselves.

## Provisioning

`UserProvisioningService.ensureUsable` runs in `UserProvisioningInterceptor` before every `/api/v1/users/**` controller, and only for a token holding `ROLE_user` (the interceptor runs before method security, so it checks the role itself instead of relying on the URL rule).

- It inserts the row with a bare `INSERT ... ON CONFLICT DO NOTHING` (no conflict target: primary key and address index alike), re-reads, and emits `UserRegistered` only from the winning insert. Its database work runs in a transaction opened with a `TransactionOperations`; the method is not `@Transactional` itself, so a Keycloak question can be asked after the transaction ended (see the orphan email flow in the saga design, 8.8).
- A new row needs a verified email (`email_verified` claim; otherwise 403 `email-not-verified`) and a `sub`.
- Display name comes from `name`, then `given_name`, then the local part of `preferred_username`.
- The token's email is read only to create the row and never written afterwards. A verified token email that differs from the row (case ignored: Keycloak lower-cases, and the unique index agrees) is counted in `user.email.token.mismatch` (no tags, never logged); nothing else happens.
- A `DELETING` or `DELETED` user gets 410 `account-deleted`; `ACTIVE` and `DELETION_SCHEDULED` pass (a scheduled account can still be read and cancelled).
- An address held by another account is answered 503 `email-being-released` (the orphan was scheduled for immediate deletion), 409 `email-already-in-use` (the holder exists in Keycloak) or 503 `identity-provider-unavailable`.
- Provisioning on every request, not only on `GET /me`, is deliberate (see `08-design-decisions.md`).

## API

- `GET /api/v1/users/me`: the account, including `status` and `deletionScheduledFor` (the frontend shows the banner and Cancel from them).
- `PATCH /api/v1/users/me`: `displayName` (at most 100 characters, non-blank, stripped) and `timezone` (one of `ZoneId.getAvailableZoneIds()`: exact case-sensitive id, no offsets like `+03:00`, no trimming; null means not sent; `@ValidTimezone` / `TimezoneValidator`). The row is read `FOR UPDATE` and checked under that lock, so concurrent edits and a concurrent schedule serialize on it (a stale write cannot happen, and `@Version` stays the event's aggregate version). A `DELETION_SCHEDULED` row answers 409 `deletion-scheduled` (the grace period is read-only); a `DELETING`/`DELETED` row answers 410 (a deletion can commit between the interceptor's check and the service's read). `email` in the body answers 400 `validation-failed` (the field is declared with `@Null` only so a request that sends it fails instead of being ignored; other unknown fields are ignored, which is Jackson 3's default and is not changed because the mapper is shared with future Kafka consumers). `UserUpdated` is emitted only if the version moved.
- `DELETE /api/v1/users/me` and `POST /api/v1/users/me/deletion/cancel`: the saga's HTTP contract, in section 6 of the saga design.
- Admin (role `admin`): `GET /api/v1/admin/users` (paged, `size` at most 100, sort `createdAt DESC, id`) and `GET /api/v1/admin/users/{id}` (includes the deletion columns and the participant rows). Read-only; write endpoints are planned (`03-roadmap.md`).
- Errors are `ProblemDetail`s from one `GlobalExceptionHandler` (see `04-conventions.md`).

## Security

- `/api/v1/users/**` requires role `user` and `/api/v1/admin/**` role `admin`: a URL rule in `SecurityConfig` plus `@PreAuthorize` on both controllers. An admin-only token gets 403 on the customer API and a customer token gets 403 on the admin API.
- Resource server whose decoder is `NimbusJwtDecoder.withJwkSetUri(jwk-set-uri)`: the keys are fetched on the first token, so startup and test contexts never call Keycloak. `issuer-uri` only validates `iss`. `AudienceValidator` requires `aud` to contain `user-service`; `KeycloakRealmRoleConverter` maps realm roles to `ROLE_<name>` (case preserved). Tokens that cannot be verified because the key endpoint is down get 503 `service-unavailable` (`TokenVerificationUnavailableFilter`).
- 401 and 403 are problem details too. Stateless, no CSRF, CORS for `http://localhost:3000`. `/actuator/health` is public and the only exposed endpoint.
- Admin accounts are staff, not customers: they exist only in Keycloak and never get a `users` row (`08-design-decisions.md`).

## Events, outbox and relay

Every state change writes its event to `outbox_events` in the same transaction; the relay publishes to `user.events.v1` (key = user id). Event types: `UserRegistered`, `UserUpdated`, `UserDeletionScheduled`, `UserDeletionCancelled`, `UserDeletionStarted` (emitted by the promoter), `UserDeletionFinalized` (emitted by the finalizer). `docs/design/01-outbox-design.md` has the design; `06-kafka-event-contract.md` the wire format.

## Deletion saga

Implemented end to end: states, HTTP endpoints, promoter, Keycloak admin client, hard-delete job, orphan email collision, the consumer of the participants' confirmations, the finalizer, the watchdog gauges. The concurrency and failure tests are done too (races between jobs, between requests and between a confirmation and the finalizer; Keycloak and Kafka down). Design, tests and roadmap: `docs/design/03-account-deletion-saga.md`.

## Metrics

| Meter | Meaning |
|---|---|
| `outbox.pending.rows`, `outbox.oldest.pending.age.seconds`, `outbox.parked.rows`, `outbox.relay.consecutive.failed.ticks` | relay gauges (outbox design, section 11) |
| `outbox.rows.published`, `outbox.batches.failed` | relay counters |
| `user.deletion.promoted`, `user.deletion.promotion.failures` | promoter |
| `user.deletion.keycloak.deleted`, `user.deletion.keycloak.failures` | Keycloak hard-delete job |
| `user.deletion.finalized`, `user.deletion.finalization.failures` | finalizer |
| `user.deletion.participant.acks{participant,outcome}` | confirmations processed: `acked`, `already_processed`, `not_pending` |
| `user.deletion.scheduled.accounts`, `user.deletion.deleting.oldest.age.seconds`, `user.deletion.keycloak.oldest.pending.age.seconds`, `user.deletion.participant.pending.acks{participant}` | the saga's watchdog gauges |
| `keycloak.admin.circuit.state` | 0 closed, 1 half open, 2 open |
| `user.email.orphan.released`, `user.email.collision.live` | orphan email handling |
| `user.email.token.mismatch` | verified token email differs from the row |

Nothing scrapes them yet: the Prometheus endpoint arrives with the observability step (`03-roadmap.md`).

## Running several instances

The service is built to run as N instances against one database and one Kafka cluster. Nothing needs a leader election or a scheduler lock: every job is made safe by the database or by the group protocol.

| Part | With N instances | Why it is safe |
|---|---|---|
| Outbox relay | one instance publishes at a time | a transaction-scoped advisory lock; the others find it taken and idle, so the order of events is kept |
| Promoter, finalizer | every instance runs them, each account is moved once | a per-account compare-and-set that repeats every condition; the loser matches nothing |
| Keycloak delete job | two instances may call Keycloak for the same account | the call is idempotent (404 is "already gone") and the stamp is a compare-and-set; the cost is duplicated work, bounded by the batch size |
| Purges (outbox, `processed_events`) | every instance runs them at the cron time | deleting the same old rows twice is harmless: the second finds them gone |
| Confirmation consumer | one consumer group, `user-service`: the instances share the topic's partitions (three), so at most three are active and the rest wait as standby | a redelivered or duplicated event is claimed once in `processed_events` (primary key); the cooperative assignor moves only the partitions that must move |
| In-memory state (retry counters of the error handler, the Keycloak circuit breaker and token) | per instance | it is only an optimisation or a protection of that instance |
| Gauges and counters | every instance reports the same database-wide gauges (aggregate with max or average, never sum); counters are per instance (sum them) | |

Proven: the races between two jobs or two services on one database (the integration tests of the promoter, finalizer, Keycloak job, confirmation consumer), between a confirmation and the finalizer, and between the customer's own requests (two `DELETE`s, a `PATCH` against a schedule, two `PATCH`es), staged and over HTTP; Kafka and Keycloak down in the middle of a deletion. Not proven: two JVMs against one broker. To size when deploying: each instance has its own connection pool (10 by default) and the relay holds a connection for its tick, so instances times pool size must stay under Postgres's `max_connections`.

## Configuration

Everything is in `services/user-service/src/main/resources/application.yaml` with a comment per setting. Secrets come only from the environment: `USER_DB_PASSWORD` and `USER_SERVICE_ADMIN_CLIENT_SECRET` (the application does not start without the second). There are no Spring profiles: one `application.yaml` holds the development values, and an environment overrides a value with an environment variable (relaxed binding: `APP_USER_DELETION_GRACE=14d`). Kafka: `spring.kafka.producer.*` is what the two producers (the relay's and the dead-letter publisher's) share, and each has a client id of its own (`app.kafka.relay-producer.client-id` and `app.kafka.dead-letter-producer.client-id`, both required); `spring.kafka.consumer.*` and `spring.kafka.listener.*` configure the confirmation listener, with every consumer setting that matters written down instead of left to the client's default, and it can be switched off with `app.user.deletion.ack-consumer.enabled`. The scheduler pool is 6 threads: a `fixedDelay` job never overlaps itself, but a slow tick of one (a relay tick against a dead Kafka, a Keycloak call that times out) must not delay the others. If virtual threads are enabled later, Boot builds a different scheduler and ignores the pool size (`spring.task.scheduling.simple.concurrency-limit` applies instead).
