# Consumer-side deduplication: `processed_events`

How a service that consumes Kafka events applies each event exactly once in effect, although Kafka delivers it at least once. It is the consumer's half of `01-outbox-design.md`: the outbox guarantees an event is delivered at least once, this makes applying it twice harmless.

## 0. Status

| Part | State |
|---|---|
| The table `processed_events` in user-service (migration V10) and its schema test | built |
| A consumer that uses it, the listener container configuration, dead-letter handling, the purge job | **not built**: no service consumes anything yet |
| First consumer | user-service's own ack consumer for the deletion saga (`03-account-deletion-saga.md`, section 8.5; roadmap step 5g) |

Everything below section 2 is the design the first consumer will be built to. When it is built this document is updated in the same change: section 6 gets the real handler names and what was learned, and section 0 changes.

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
- `handler` is part of the key because one service may legitimately apply one event in two independent handlers; each records its own claim, and a second handler can never be starved by the first having claimed the event.
- `event_type` and `aggregate_id` are diagnostics only ("which events did we process for user 5?").
- Retention: rows older than the topic's retention can be purged (a redelivery cannot be older than the topic). The table's comment says 30 days. The purge job is not built; it is a daily batched `DELETE` like the outbox purge (`OutboxPurgeJob`), written with the first consumer.

Every PostgreSQL-backed consumer gets this table in its own database.

## 3. The processing pattern

One database transaction per record:

1. **Claim the event.** `INSERT INTO processed_events (event_id, handler, event_type, aggregate_id) VALUES (...) ON CONFLICT DO NOTHING`. Zero rows means it was applied before: return, and the offset still commits.
2. **Stale-event guard**, for events that carry state applied to a projection: compare `aggregateVersion` with the projection's stored version and ignore anything not greater (section 5).
3. **Apply the effect**, in the same transaction.
4. **Write outbox rows** for any events the effect produces, in the same transaction.
5. **Commit.** The container then commits the offset, because the listener returned without an exception.

What happens when:

- **Double delivery**: step 1 inserts nothing, the listener returns, the offset commits, nothing else happens.
- **A crash after the commit but before the offset commit**: redelivery takes the same path and does nothing.
- **A failure in step 2 to 4**: the exception rolls the transaction back, including the `processed_events` row; the error handler backs off and the record is redelivered for a clean retry.

## 4. The consumer container

The rules every listener follows (they are also in `docs/open/06-kafka-event-contract.md`):

- One consumer group per service, named after it. Acknowledgement is per record, after the listener returns (`AckMode.RECORD`).
- The value is read as an `EventEnvelope` with `data` kept as a tree; the handler dispatches on `eventType` and then converts `data`. Unknown fields are ignored. An unknown `schemaVersion` is a loud failure, not a silent misreading: it goes to the dead-letter topic.
- **Poison records** (a message that cannot be deserialized, an unknown schema version, a payload that can never be applied) go to `<topic>.dlt` through `ErrorHandlingDeserializer` and a `DeadLetterPublishingRecoverer`. Spring's default dead-letter name is `<topic>-dlt`, so a custom destination resolver gives the dotted name.
- **`DataAccessException`s retry indefinitely and never go to the dead-letter topic.** The database being down says nothing about the record, and skipping it would break the order of that aggregate's events.
- `concurrency` is at most the number of partitions; no `@Async` in a listener (it would acknowledge before the work is done).
- A dead-letter topic needs its own alert: a poison `UserDeletionStarted` in a participant's dead-letter topic is otherwise silent until the saga's stall alert fires.

## 5. Out-of-order events

`processed_events` only answers "seen before?". Order within one aggregate comes from the Kafka key and the single active relay, so normally a consumer never sees version 3 before version 2 for one user. The exceptions are a replay, a manual re-drive from a dead-letter topic, and the relay's pipelined send (outbox design, section 6), all of which can present an older event after a newer one. The guard is `aggregateVersion` compared with the projection's stored version: apply only if greater. Consumers of *lifecycle* events additionally treat deletion as terminal: once a user has a tombstone, any event for them is ignored whatever its version (`UserDeletionStarted` wins over any older event).

## 6. Which consumers need it

Use `processed_events` when the side effect is relational and has no natural idempotency key. Use the natural key when one exists. Use nothing when the sink is idempotent by construction.

| Consumer | Needs `processed_events`? | Why |
|---|---|---|
| user-service, deletion confirmations from participants | yes (cheap: the effect is a flag flip that is idempotent anyway, but the table keeps every handler uniform and replays observable) | first consumer |
| device-service, user events | yes | side effects are relational and produce further events |
| alert-service, user and device events | yes | same |
| notification-service, user events | yes (projection updates) | |
| notification-service, its own request queue | no | a natural unique idempotency key (`ON CONFLICT (idempotency_key)`) beats a synthetic one |
| usage-service, readings | no | InfluxDB's primary key makes the write idempotent, and the sink is not Postgres, so a table there would not be transactional with the write |
| ingestion-service, the compacted device registry | no | applying a compacted state topic is idempotent by nature: the last value wins |
| ai-insight-service, user events | yes | a purge is idempotent, but tracking makes replays observable |

(Services other than user-service are not built yet; this column is the intended design.)

## 7. How user-service will use it first

The deletion saga needs one handler per participant service. Each consumes the participant's confirmation event on that service's topic (the first is `UserDevicesDeleted` on `device.events.v1`) and runs, inside the claim transaction:

```sql
UPDATE user_deletion_participants SET acked_at = CURRENT_TIMESTAMP
 WHERE user_id = :id AND participant = :participant AND acked_at IS NULL;
```

Zero rows means the participant was not required for this deletion, or already confirmed: ignore. The `users` row is not touched, so an ack never conflicts with another update and never bumps the version. A registry of handlers, checked at startup against `app.user.deletion.required-participants`, makes a misspelled participant fail the boot instead of silently stalling every deletion. Details: saga design, sections 8.5 and 11.

## 8. Testing it

- Hand-crafted messages, no running producer: a delivered event is applied once; the same event twice changes nothing (the second claim returns zero rows); a failure in the effect rolls the claim back so the retry applies it; an older version after a newer one is ignored; a tombstoned user's events are ignored; an unknown schema version and a malformed value end in the dead-letter topic; a `DataAccessException` retries and is never dead-lettered.
- The consumer half of the contract tests: read the fixture file from `contracts`, feed it to the handler, assert the effect (`docs/open/06-kafka-event-contract.md`).
- A Testcontainers test for the container configuration: record acknowledgement after the effect, the `.dlt` topic name, concurrency.
- The schema test `ProcessedEventSchemaIT` exists: the primary key per `(event_id, handler)` and `ON CONFLICT DO NOTHING` returning 0 on a redelivery.

## 9. Roadmap

- With saga step 5g: the listener container configuration, the dead-letter resolver and topic (`kafka-topics.sh`), the first handlers, the purge job, the consumer tests above. Update sections 0 and 7 then.
- With every later consumer: the same table in its database, the same pattern; the exception cases in section 6.
