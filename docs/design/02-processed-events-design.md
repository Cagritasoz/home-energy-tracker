# Consumer-side deduplication: `processed_events`

How a service that consumes Kafka events applies each event exactly once in effect, although Kafka delivers it at least once. It is the consumer's half of `01-outbox-design.md`: the outbox guarantees an event is delivered at least once, this makes applying it twice harmless.

## 0. Status

| Part | State |
|---|---|
| The table `processed_events` in user-service (migration V3) and its schema test | built |
| The shared consumer pieces in user-service's `consumer` package: `ProcessedEvents` (the claim and the purge), `ProcessedEventsPurgeJob`, `EventEnvelopeReader`, `PoisonEventException`, `KafkaConsumerConfig` (the error handler) | built |
| The first consumer: `ParticipantAckListener` and `ParticipantAckService` (package `deletion.ack`), which apply the deletion saga's participant confirmations (`03-account-deletion-saga.md`, section 8.5) | built |
| Consumers in other services, the dead-letter topic of `user.events.v1` (created with its first consumer), alerts on dead-letter topics | not built |

The design below is what the code does; sections 3 to 8 name the classes. The version guard (section 5) and the tombstone are written by the first consumer that applies state: the participant confirmations do not need them (confirming twice is the same as once).

## 1. Why it exists

The consumer commits its offset *after* processing, so a crash between the side effect and the commit redelivers the record. Offsets therefore only prevent *losing* records, never double processing. A relay retry can also publish the same event twice (outbox design, section 8). `processed_events` records "I have already applied event X" in the **same database transaction** as applying it, so the side effect and the memory of having done it are atomic: either both happened or neither did.

## 2. The table

```sql
CREATE TABLE processed_events (
    event_id      uuid        NOT NULL,
    handler       text        NOT NULL,
    event_type    text        NOT NULL,
    aggregate_id  text        NOT NULL,
    processed_at  timestamptz NOT NULL DEFAULT CURRENT_TIMESTAMP,
    CONSTRAINT pk_processed_events PRIMARY KEY (event_id, handler)
);
CREATE INDEX idx_processed_events_processed_at ON processed_events (processed_at);
```

- `event_id` is the outbox row's `id`: the producer-generated id that survives re-publishing. Redeliveries and re-publishes carry the same value, which is the whole point.
- `handler` is part of the key because one service may legitimately apply one event in two independent handlers; each records its own claim, and a second handler can never be starved by the first having claimed the event. The saga's handlers are named `participant-ack:<participant>`.
- `event_type` and `aggregate_id` are diagnostics only ("which events did we process for user 5?").
- **Retention.** A redelivery cannot be older than the topic's retention, so older rows are dead weight. `ProcessedEventsPurgeJob` deletes rows older than `app.processed-events.purge.retention-days` (30) in batches of `batch-size` (1000), oldest first, until a batch comes back short, once a day (`cron`, 03:30). The boundary is strict: a row exactly 30 days old stays.

Every PostgreSQL-backed consumer gets this table in its own database.

## 3. The processing pattern

One database transaction per record:

1. **Claim the event.** `ProcessedEvents.claim(eventId, handler, eventType, aggregateId)` runs `INSERT ... ON CONFLICT DO NOTHING` and answers whether the row was inserted. False means it was applied before: return, and the offset still commits. The method is `@Transactional(propagation = MANDATORY)`, like the outbox writer: a claim made outside the effect's transaction would survive the effect's failure, and the retry would then skip an event that was never applied.
2. **Stale-event guard**, for events that carry state applied to a projection: compare `aggregateVersion` with the projection's stored version and ignore anything not greater (section 5).
3. **Apply the effect**, in the same transaction.
4. **Write outbox rows** for any events the effect produces, in the same transaction.
5. **Commit.** The container then commits the offset, because the listener returned without an exception.

What happens when:

- **Double delivery**: step 1 inserts nothing, the listener returns, the offset commits, nothing else happens.
- **Two copies at the same moment**: the second `INSERT` waits for the first transaction to finish and then finds its row, so the effect is applied once.
- **A crash after the commit but before the offset commit**: redelivery takes the same path and does nothing.
- **A failure in step 2 to 4**: the exception rolls the transaction back, including the `processed_events` row; the error handler backs off and the record is redelivered for a clean retry.

## 4. The consumer container

How a listener is configured in user-service (`application.yaml`, `KafkaConsumerConfig`), and what each setting is for:

- **One consumer group per service**, named after it (`spring.kafka.consumer.group-id`). A group with no committed offset starts from the beginning (`auto-offset-reset: earliest`): an event the service has never seen must not be skipped because it was published before the group existed.
- **Every setting that matters is written down** in `application.yaml` (`spring.kafka.consumer.*`), the client's defaults included, so a client upgrade cannot change how the group behaves and the behavior is readable in one place: `read_committed`; a poll of at most 100 records (each is its own transaction and a failure discards the rest of the poll, so a small poll bounds the wasted work); heartbeat 3 s, session timeout 45 s, poll interval 5 min (far above the 30 s of the longest database pause); the classic group protocol with the cooperative sticky assignor; no topic auto-creation (topics come from `kafka-topics.sh`). `KafkaConfigurationTest` pins them.
- **Acknowledgement per record, after the listener returned** (`spring.kafka.listener.ack-mode: record`, `enable-auto-commit: false`). Automatic commits would acknowledge records the listener has not finished.
- **Values are read as strings and parsed by the listener** (`EventEnvelopeReader`: `data` stays a tree, unknown fields are ignored, an event type the handler does not know is ignored). A string cannot fail to deserialize, so everything wrong with a message surfaces as an ordinary exception in one place, and the record can be republished to the dead-letter topic exactly as it arrived (by the dead-letter producer, below). (`ErrorHandlingDeserializer` would turn a failure into a record whose value the dead-letter publisher could not send with a string serializer.)
- **Three kinds of failure, three policies** (`KafkaConsumerConfig`):
  - *Poison* (`PoisonEventException`: not JSON, a field every event has is missing or invalid, an unsupported schema version, an aggregate id that is not what the handler needs): never retried. The record goes to `<topic>.dlt` at once, with the same key and value and headers naming the original topic and the reason, and the consumer moves on, so one bad record cannot block the partition behind it. Spring's default dead-letter name is `<topic>-dlt`, so a destination resolver gives the dotted name; the producer picks the partition from the key. The exception message names what is wrong and never quotes the record, because it travels in a header and an event can hold an email address.
  - *The database is the problem* (a `DataAccessException` anywhere in the cause chain): retried for as long as it takes, pausing 1 s and doubling to 30 s, and **never dead-lettered**. It says nothing about the record, and skipping the record would lose an event or apply a later one first and break the order of that aggregate's events.
  - *Anything else* (a bug): three more attempts a second apart, then the dead-letter topic, so a record that trips a bug is parked for a human instead of retried forever.
- **The dead-letter publisher is a producer of its own**: `deadLetterKafkaTemplate` over `deadLetterProducerFactory`, declared in `KafkaProducerConfig` next to the relay's `relayKafkaTemplate` and `relayProducerFactory`. Both factories are built from the same shared settings (`spring.kafka.producer.*`) and differ only in the client id (`app.kafka.relay-producer.client-id` and `app.kafka.dead-letter-producer.client-id`, both required: `KafkaClientIdProperties`). The durability settings belong to every producer of the service, and the relay's fail-fast timeouts suit a dead-letter send too, because a failed send leaves the record unrecovered and the error handler tries it again. Each template is injected by name (`@Qualifier`), and both carry the silent `ProducerListener`, so a failed send never logs a payload. To give the dead-letter producer settings of its own, add them in its factory method, before the shared builder creates the factory.
- **Several instances** share one group (`user-service`): the topic's partitions are split among them, at most one consumer per partition is active, and the rest wait as standby. A redelivery after a rebalance is harmless because of the claim.
- `spring.kafka.listener.concurrency` is 1 and must stay at most the partition count; no `@Async` in a listener (it would acknowledge before the work is done).
- A listener's `autoStartup` is a property (`app.user.deletion.ack-consumer.enabled`), because a test context without a broker must not start a consumer that keeps trying to reach one.
- A dead-letter topic needs its own alert: a poison `UserDeletionStarted` in a participant's dead-letter topic is otherwise silent until the saga's stall alert fires. `device.events.v1.dlt` exists (`infra/kafka/kafka-topics.sh`); the alert arrives with the Prometheus endpoint.

## 5. Out-of-order events

`processed_events` only answers "seen before?". Order within one aggregate comes from the Kafka key and the single active relay, so normally a consumer never sees version 3 before version 2 for one user. The exceptions are a replay, a manual re-drive from a dead-letter topic, and the relay's pipelined send (outbox design, section 6), all of which can present an older event after a newer one. The guard is `aggregateVersion` compared with the projection's stored version: apply only if greater. Consumers of *lifecycle* events additionally treat deletion as terminal: once a user has a tombstone, any event for them is ignored whatever its version (`UserDeletionStarted` wins over any older event).

## 6. Which consumers need it

Use `processed_events` when the side effect is relational and has no natural idempotency key. Use the natural key when one exists. Use nothing when the sink is idempotent by construction.

| Consumer | Needs `processed_events`? | Why |
|---|---|---|
| user-service, deletion confirmations from participants | yes, built (cheap: the effect is a flag flip that is idempotent anyway, but the table keeps every handler uniform and replays observable) | first consumer |
| device-service, user events | yes | side effects are relational and produce further events |
| alert-service, user and device events | yes | same |
| notification-service, user events | yes (projection updates) | |
| notification-service, its own request queue | no | a natural unique idempotency key (`ON CONFLICT (idempotency_key)`) beats a synthetic one |
| usage-service, readings | no | InfluxDB's primary key makes the write idempotent, and the sink is not Postgres, so a table there would not be transactional with the write |
| ingestion-service, the compacted device registry | no | applying a compacted state topic is idempotent by nature: the last value wins |
| ai-insight-service, user events | yes | a purge is idempotent, but tracking makes replays observable |

(Services other than user-service are not built yet; the rows other than the first are the intended design.)

## 7. How user-service uses it

The deletion saga has one handler per participant service. `ParticipantAckListener` reads the participants' topics (the registry `ParticipantAcks` says which: today `devices` on `device.events.v1`, event type `UserDevicesUnclaimed`, schema version 1), parses the envelope, and hands a recognised confirmation to `ParticipantAckService`, which runs in one transaction:

```sql
INSERT INTO processed_events (event_id, handler, event_type, aggregate_id) VALUES (...) ON CONFLICT DO NOTHING;
UPDATE user_deletion_participants SET acked_at = CURRENT_TIMESTAMP
 WHERE user_id = :id AND participant = :participant AND acked_at IS NULL AND waived_at IS NULL;
```

The outcome is `ACKED` (a pending row was confirmed), `ALREADY_PROCESSED` (the claim was already there) or `NOT_PENDING` (new event, nothing pending: the participant is not required for this deletion, the row is confirmed or waived, or there is no such deletion; ignored, the claim is kept), counted in `user.deletion.participant.acks{participant,outcome}`. The `users` row is not touched, so an ack never conflicts with another update and never bumps the version. The registry is also what `app.user.deletion.required-participants` is checked against when the application starts. Details: saga design, sections 8.5 and 11.

## 8. Tests

- `EventEnvelopeReaderTest` (every refusal is a poison exception and none quotes the message), `KafkaConsumerConfigTest` (the database policy: which failures count, the pauses grow to 30 s and never stop), `ProcessedEventsPurgeJobTest`.
- `ProcessedEventsIT` (the claim, the strict purge boundary, oldest first, batch limit), `ProcessedEventSchemaIT` (the primary key per `(event_id, handler)`, `ON CONFLICT DO NOTHING` returning 0 on a redelivery).
- `ParticipantAckAtomicityIT`: with real commits, a failure after the claim rolls the claim back and the retry applies the event; two copies at once are applied once; a claim outside a transaction is refused.
- `ParticipantAckConsumerIT`: a real broker, a real Postgres and messages produced by hand: duplicates applied once, unknown event types ignored, poison to `<topic>.dlt` unchanged and never retried, a failing database waited out and never dead-lettered, the records behind a poison record still applied.
- `KafkaProducersWiringTest`, `KafkaClientIdPropertiesTest` and `KafkaConfigurationTest` (no Docker): the context has exactly the two templates and the two factories and none of Boot's, each producer has its own client id (a stale one in the shared settings is overwritten, a missing one fails the start-up) and the shared settings, both get the connection details, the factory customizers and the silent listener, the outbox publisher gets the relay's template and the error handler the dead-letter one, and the consumer settings of the yaml are the written-down ones.
- The contract fixture `UserDevicesUnclaimed.v1.json` is the message the listener tests and the end-to-end tests feed in (`ParticipantAckListenerTest`, `DeletionSagaDevicesFlowIT`), which is the consumer half of the contract tests.
- Not yet: a consumer that applies state and so needs the version guard and a tombstone (the first one is device-service's handling of user events).

## 9. Roadmap

- With the first service that consumes `user.events.v1`: its dead-letter topic `user.events.v1.dlt` in `kafka-topics.sh`, the same table in that service's database, the same pattern, the version guard and the tombstone of section 5.
- Dead-letter alerts with the Prometheus endpoint (`docs/open/03-roadmap.md`).
- A second participant in user-service is one entry in `ParticipantAcksConfig` (saga design, section 8.5).
