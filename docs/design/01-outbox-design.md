## 4. Outbox Design

### 4.1 Why it exists (recap in one paragraph)

A service that writes to its database and then publishes to Kafka has two writes with no transaction spanning both. Commit-then-crash loses the event; publish-then-crash publishes an event about a change that never happened. The outbox makes the event part of the *same* database transaction as the change. A separate publisher then moves committed rows to Kafka. Since the publisher can crash between "sent" and "marked sent", it may send twice — which is why consumers must be idempotent (§5). The guarantee achieved is at-least-once with per-aggregate ordering.

### 4.2 DDL (identical in user-, device- and alert-service)

```sql
CREATE TABLE outbox (
    seq               bigint      GENERATED ALWAYS AS IDENTITY PRIMARY KEY,   -- publish order
    id                uuid        NOT NULL,                                   -- event id; becomes the Kafka header / consumer dedupe key
    aggregate_type    text        NOT NULL,                                   -- 'User', 'Device', ...
    aggregate_id      text        NOT NULL,                                   -- Kafka message key
    aggregate_version bigint,                                                 -- entity version after the change; null if producer is not the aggregate owner
    event_type        text        NOT NULL,                                   -- 'UserUpdated'
    schema_version    int         NOT NULL DEFAULT 1,
    topic             text        NOT NULL,                                   -- 'user.events.v1'
    payload           jsonb       NOT NULL,                                   -- the COMPLETE event body (envelope + data)
    correlation_id    uuid,                                                   -- the request/trace that started the chain
    causation_id      uuid,                                                   -- the event id that caused this one, if any
    occurred_at       timestamptz NOT NULL,                                   -- domain time of the change
    created_at        timestamptz NOT NULL DEFAULT now(),                     -- row insert time
    published_at      timestamptz,                                            -- null = pending
    attempts          int         NOT NULL DEFAULT 0,
    last_error        text,
    CONSTRAINT outbox_id_uq UNIQUE (id)
);
 
CREATE INDEX outbox_pending_idx ON outbox (seq) WHERE published_at IS NULL;
CREATE INDEX outbox_published_purge_idx ON outbox (published_at) WHERE published_at IS NOT NULL;
```

### 4.3 Every column, and the ones deliberately left out

| Column | Necessary? | Purpose |
|---|---|---|
| `seq` | yes | Monotonic insert order within this database. `ORDER BY seq` is the publish order. A `uuid` primary key cannot give you order; a timestamp can tie. Identity is a sequence, and sequences are assigned at insert time — a transaction that started earlier but committed later can have a *lower* seq that becomes visible *after* a higher one. The single-publisher design (§4.4) handles that. |
| `id` | yes | The event id. Generated *in the outbox row*, so a re-publish carries the same id and consumers can deduplicate. Also the value Debezium's outbox router places in the `id` Kafka header. |
| `aggregate_type` | yes | Lets one table serve several aggregates; Debezium routes on it (`aggregatetype` by default; the column name is configurable). |
| `aggregate_id` | yes | The Kafka key. All events of one aggregate land on one partition, giving per-aggregate ordering. `text`, not `uuid`, because Debezium's router expects a string key and because a future aggregate might not have a UUID id. |
| `aggregate_version` | yes, nullable | The entity's `version` after the change (§18). Consumers use it to reject stale events. Null for events that are not "state of one aggregate" (e.g. `UserDevicesDeleted` emitted by device-service about a user). |
| `event_type` | yes | Consumer dispatch and Debezium header. |
| `schema_version` | yes | Version of the payload shape; consumers branch on it during migrations. |
| `topic` | yes (chosen) | Which topic to publish to. The alternative is deriving the topic from `aggregate_type` in code; storing it makes the publisher generic and matches Debezium's `route.topic.replacement`. |
| `payload` | yes | The **entire** event JSON, including the envelope fields (`eventId`, `aggregateVersion`, `occurredAt`, …). Reason: with Debezium the payload column is forwarded verbatim as the Kafka value; if the envelope lived in other columns, switching to CDC would change the value shape and every consumer. Storing the full body means the consumer sees byte-identical values from either publisher. |
| `correlation_id` | yes | The trace/request id that started the causal chain (e.g. the `DELETE /users/me` request). Cheap, and it makes the deletion saga traceable across four services. |
| `causation_id` | yes, nullable | The id of the event this event was produced *in response to* (device-service's `DeviceDeleted` has `causation_id` = the `UserDeleted` event id). Null for events caused by an HTTP request. |
| `occurred_at` | yes | Domain time. Copied into the payload too. |
| `created_at` | yes | Row insert time; the gap `published_at − created_at` is your outbox latency metric. |
| `published_at` | yes (polling) | Null = pending. This replaces a `status` enum: there are exactly two states, and a nullable timestamp encodes both and *when*. Debezium ignores it. |
| `attempts`, `last_error` | yes (polling) | Diagnostics for a broker that refuses the message (e.g. oversized). Debezium ignores them. |
| `status` enum | **no** | `published_at IS NULL` is the status. An enum invites `PROCESSING` states that need lease expiry logic the advisory lock already makes unnecessary. |
| `headers jsonb` | **no** | Trace context goes into the Kafka headers from the publisher's current span (which is the row's `correlation_id`); nothing else needed. |
| `partition` | **no** | Derived from the key by the producer; storing it would pin partition counts into data. |

### 4.4 How it works — the publisher (CORRECTION 1 applied)

```java
@Component
class OutboxPublisher {
    private static final long LOCK_KEY = 7_331_001L;            // arbitrary, unique per outbox table
 
    @Scheduled(fixedDelay = 300)
    @Transactional
    public void publishBatch() {
        // 1. Become the single active publisher for this database, or give up until the next tick.
        Boolean got = jdbc.queryForObject("SELECT pg_try_advisory_xact_lock(?)", Boolean.class, LOCK_KEY);
        if (!Boolean.TRUE.equals(got)) return;
 
        // 2. Oldest pending rows in insert order.
        List<OutboxRow> rows = jdbc.query("""
            SELECT seq, id, topic, aggregate_id, event_type, schema_version, payload, correlation_id
            FROM outbox WHERE published_at IS NULL ORDER BY seq LIMIT 200
            """, rowMapper);
 
        // 3. Send each; wait for broker acks in order; stop at the first failure so ordering holds.
        for (OutboxRow r : rows) {
            ProducerRecord<String, String> rec = new ProducerRecord<>(r.topic(), r.aggregateId(), r.payload());
            rec.headers().add("event_id", r.id().toString().getBytes(UTF_8))
                         .add("event_type", r.eventType().getBytes(UTF_8))
                         .add("schema_version", Integer.toString(r.schemaVersion()).getBytes(UTF_8));
            try {
                kafka.send(rec).get(5, TimeUnit.SECONDS);                 // idempotent producer, acks=all
                jdbc.update("UPDATE outbox SET published_at = now(), attempts = attempts + 1 WHERE seq = ?", r.seq());
            } catch (Exception e) {
                jdbc.update("UPDATE outbox SET attempts = attempts + 1, last_error = ? WHERE seq = ?", e.toString(), r.seq());
                break;                                                    // keep order: do not skip ahead
            }
        }
    }   // 4. Commit releases the advisory lock; another instance may win the next tick.
}
```

Step by step:
1. `pg_try_advisory_xact_lock` is a database-level mutex held for the duration of the transaction. Every instance runs the scheduler; only the one that obtains the lock does work; the others return immediately. If the holder crashes, PostgreSQL releases the lock when the connection dies. This is leader election with zero extra infrastructure, and it is what guarantees that two publishers never interleave one aggregate's events.
2. Rows are read in `seq` order. The "earlier transaction, later commit" edge case (a row with seq 40 becoming visible after seq 41 was already published) is the one ordering hole in polling outboxes. Two mitigations: keep the business transaction short (the outbox insert is the last statement before commit), and accept that this can reorder two *different* aggregates' events, never the same aggregate's — because the same aggregate's row is `@Version`-locked, two transactions on it cannot overlap.
3. Send synchronously in order; on the first failure stop, mark, and let the next tick retry from the oldest pending row. This is the retry mechanism — no exponential backoff inside the loop, the 300 ms tick plus a circuit breaker on the producer (opens after N consecutive failures, tick returns immediately while open) is enough.
4. Crash between `send` acknowledged and `UPDATE … published_at` committed: the row is still pending; next tick re-sends it. Same `id`, same key, same payload → consumer's `processed_events` deduplicates. This is the duplicate-publishing case, and it is handled by design rather than prevented.
   Purge: a daily job deletes rows with `published_at < now() − 7 days`. Seven days is the **replay window**: an admin endpoint can reset `published_at = NULL` for a range of `seq` (or one aggregate) to re-emit events, e.g. to rebuild a projection.

### 4.5 Ordering and Kafka partitioning

The Kafka key is `aggregate_id`, so all events for user 5 hash to one partition and are appended in the order the publisher sent them, which is `seq` order, which is commit order for that aggregate. Across different aggregates there is no ordering guarantee and none is needed.

### 4.6 Event replay

Two mechanisms, both via the same table: (a) reset `published_at` for chosen rows; (b) for a full rebuild of a projection beyond 7 days, the *consumer* resets its group offset to the beginning of the topic (Kafka retention must cover it — 30 days for `user.events.v1`; or use a compacted state topic like `device.registry.v1`). Replaying is safe because consumers deduplicate by `id` and reject stale versions.

### 4.7 Future Debezium integration

Debezium (change data capture reading PostgreSQL's write-ahead log) replaces the polling publisher with a connector that streams every `INSERT INTO outbox` to Kafka. Compatibility of this table with Debezium's Outbox Event Router:

| Router expectation | This table | Config |
|---|---|---|
| id column | `id` | `table.field.event.id=id` |
| key column | `aggregate_id` | `table.field.event.key=aggregate_id` |
| type column | `event_type` | `table.field.event.type=event_type` |
| payload column | `payload` | `table.field.event.payload=payload` |
| routing | `topic` or `aggregate_type` | `route.by.field=topic`, `route.topic.replacement=${routedByValue}` |
| extra headers | `schema_version`, `correlation_id` | `table.fields.additional.placement=schema_version:header,correlation_id:header` |

What changes when switching: stop the polling scheduler (a property), enable the connector, and change the transaction to `INSERT` then immediately `DELETE` the outbox row in the same transaction (Debezium sees the insert in the WAL; the delete keeps the table empty). `published_at`, `attempts`, `last_error` become unused columns — harmless. The Kafka value is byte-identical because `payload` already holds the full event.

### 4.8 Event versioning / schema evolution

`schema_version` starts at 1. Additive changes (new optional field) do not bump it; consumers ignore unknown fields. A breaking change bumps it; consumers that only understand version 1 route unknown versions to their DLT with a clear error, which is a deliberate loud failure rather than silent misreading. The topic name's `.v1` is for *transport-level* incompatibility (e.g. changing the key), which is rarer.

The event body (this is what `payload` contains and what consumers receive):

```json
{
  "eventId": "8f3c2a5e-1d4b-4e2a-9c1f-6b7d8e9f0a1b",
  "eventType": "UserUpdated",
  "schemaVersion": 1,
  "aggregateType": "User",
  "aggregateId": "2b6d9d5e-...",
  "aggregateVersion": 3,
  "occurredAt": "2026-09-14T10:15:30.123Z",
  "correlationId": "c0ffee00-...",
  "causationId": null,
  "producer": "user-service",
  "data": { "email": "cagri@example.com", "displayName": "Çağrı", "timezone": "Europe/Istanbul" }
}
```
 
---