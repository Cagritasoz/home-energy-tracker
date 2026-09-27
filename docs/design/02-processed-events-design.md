## 5. processed_events Design
 
### 5.1 Why it exists
 
Kafka delivers at least once: the consumer commits offsets *after* processing, so a crash between the side effect and the commit redelivers the record. Offsets alone therefore cannot prevent double processing — they only prevent *losing* records. `processed_events` records "I have already applied event X" in the *same transaction* as applying it, so the side effect and the memory of having done it are atomic.
 
### 5.2 DDL (every PostgreSQL-backed consumer: device-, alert-, notification-, ai-, user-service)
 
```sql
CREATE TABLE processed_events (
    event_id       uuid        NOT NULL,
    handler        text        NOT NULL,                 -- listener name, e.g. 'UserDeletedHandler'
    event_type     text        NOT NULL,
    aggregate_id   text        NOT NULL,
    processed_at   timestamptz NOT NULL DEFAULT now(),
    PRIMARY KEY (event_id, handler)
);
CREATE INDEX processed_events_purge_idx ON processed_events (processed_at);
```
 
- `event_id` is the outbox `id` — the producer-generated id that survives re-publishing. Redeliveries and re-publishes carry the same value; that is the whole point.
- `handler` is part of the key because one service may legitimately apply one event in two independent listeners (alert-service applies `UserDeleted` to rules *and* to tombstones — in one handler in this design, but the column costs nothing and prevents a future bug).
- `event_type` and `aggregate_id` are diagnostics only ("which events did we process for user 5?").
- Retention: rows older than the topic's retention (30 days) can be purged — a redelivery cannot be older than the topic.
### 5.3 Processing pattern
 
```java
@KafkaListener(topics = "user.events.v1", groupId = "device-service")
@Transactional                                   // one DB transaction per record
public void onUserEvent(ConsumerRecord<String, String> rec) {
    Event ev = mapper.readValue(rec.value(), Event.class);
 
    // 1. Idempotency: try to claim the event id. Zero rows = already applied → return (offset still commits).
    int inserted = jdbc.update("""
        INSERT INTO processed_events (event_id, handler, event_type, aggregate_id)
        VALUES (?, 'UserEventHandler', ?, ?) ON CONFLICT DO NOTHING""",
        ev.eventId(), ev.eventType(), ev.aggregateId());
    if (inserted == 0) return;
 
    // 2. Stale-event guard (only for state-carrying events applied to a projection; see §17).
    // 3. Business effect.
    switch (ev.eventType()) {
        case "UserDeleted" -> deviceDeletion.deleteAllFor(UUID.fromString(ev.aggregateId()), ev);
        default -> { /* ignore types this service does not care about */ }
    }
    // 4. Outbox rows for resulting events are inserted here, inside the same transaction.
}   // 5. Commit. Spring Kafka commits the offset after the listener returns without exception.
```
 
What happens on double delivery: step 1 inserts zero rows, the method returns, the offset is committed, nothing else happens. What happens on crash after commit but before offset commit: redelivery → same path → no-op. What happens on failure in step 3: exception → transaction rolls back (including the `processed_events` row) → error handler backs off → record redelivered → clean retry.
 
### 5.4 Which consumers need it
 
| Consumer | Needs `processed_events`? | Why |
|---|---|---|
| device-service ← `user.events.v1` | yes | side effects are relational and produce further events |
| alert-service ← user/device events | yes | same |
| notification-service ← `notification.requests.v1` | **no** for the command — `ON CONFLICT (idempotency_key)` is a natural idempotency key that already exists; **yes** for `user.events.v1` (projection updates) | a natural unique key is strictly better than a synthetic one when it exists |
| user-service ← `UserDevicesDeleted` | yes (cheap; the effect is a flag flip, which is idempotent anyway, but the table keeps the pattern uniform) | |
| ai-insight-service ← `UserDeleted` | yes | purge is idempotent, but tracking makes replays observable |
| usage-service ← `energy.readings.v1` | **no** | InfluxDB's primary key makes the write idempotent; the sink is not PostgreSQL, so a table there would not be transactional with the write anyway |
| ingestion-service ← `device.registry.v1` | **no** | applying a compacted state topic is idempotent by nature (last value wins) |
 
The principle: use `processed_events` when the side effect is relational and has no natural idempotency key; use the natural key when one exists; use nothing when the sink is idempotent by construction.
 
### 5.5 Out-of-order events
 
`processed_events` only answers "seen before?". Order within one aggregate is guaranteed by the Kafka key and the single publisher, so in normal operation a consumer never sees version 3 before version 2 for user 5. The exception is a **replay** or a **manual re-drive from a DLT**, which can present old events again. The guard for that is `aggregate_version` compared against the projection's stored version — applied in §11 and §17. Consumers of *lifecycle* events (`UserDeleted`) additionally treat deletion as terminal: any event for an aggregate that has a tombstone is ignored regardless of version.
