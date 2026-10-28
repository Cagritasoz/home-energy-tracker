# Kafka event contract

How services talk over Kafka. The producer side is built in user-service; the consumer rules below are what the first consumer will follow (`docs/design/02-processed-events-design.md`).

## Envelope and headers

User and device events (`user.events.v1`, later `device.events.v1`, …) use the shared `contracts` module. The record value is the JSON-serialized `EventEnvelope<T>`; there are no Java class names and no `__TypeId__` header.

| Envelope field | Notes |
|---|---|
| `eventId` | generated once, in the envelope; also the outbox row's `id` and the consumers' deduplication key |
| `eventType` | a `String`, not an enum: consumers survive a producer adding a type. Producers use `UserEventType.value()` |
| `schemaVersion` | version of the payload shape, at least 1 |
| `aggregateType`, `aggregateId` | `User`, the user id as text |
| `aggregateVersion` | the entity version after the change; nullable; has gaps, so compare with `>` |
| `occurredAt` | domain time, ISO-8601 |
| `correlationId`, `causationId` | nullable; the originating trace id, and the event that caused this one |
| `producer` | `user-service` |
| `data` | the event-specific part |

Kafka headers set by the relay: `event_id`, `event_type`, `schema_version`, and `correlation_id` when the row has one (names in `EventHeaders`). The record **key** is `aggregateId` (text, `StringSerializer`, never `UUIDSerializer`), so one aggregate's events share a partition and stay ordered.

## User events

On `user.events.v1`, producer user-service, aggregate type `User`:

| Event | `data` |
|---|---|
| `UserRegistered`, `UserUpdated` | `email`, `displayName`, `timezone` |
| `UserDeletionScheduled` | `deletionScheduledFor` |
| `UserDeletionCancelled`, `UserDeletionStarted`, `UserDeletionFinalized` | empty (the time is the envelope's `occurredAt`) |

The deletion events and who does what with them: saga design, section 7.

## Contract fixtures

Every event type has one example envelope per schema version in `contracts/src/main/resources/events/<EventType>.v<N>.json`. They live in the `contracts` jar (not in a service's test resources) so any service's tests read them without a test-jar.

- A released fixture is frozen: a breaking change adds `<EventType>.v<N+1>.json` and keeps the old file, because old messages may still be in a topic.
- Each event type has exactly one producer, so its producer test lives in that producer's service and the file is shared; every consumer of the type reads the same file.
- Producer side (built): `UserEventContractTest` runs the real `OutboxService.record*` and compares the saved payload with the fixture, field for field. Consumer side: read the file, feed it to the handler, assert the effect; written with the first consumer.

## Consumers

- Read the envelope with `data` as a tree, dispatch on `eventType` (also in the `event_type` header), then convert `data`. Unknown fields are ignored (Jackson 3 default); an unknown `schemaVersion` goes to the dead-letter topic.
- One consumer group per service, named after it. Record-level acknowledgement after the side effect. The `processed_events (event_id, handler)` row is inserted in the same transaction as the side effect.
- Poison and deserialization errors go to `<topic>.dlt` (`ErrorHandlingDeserializer` plus `DeadLetterPublishingRecoverer`, with a destination resolver because Spring's default is `<topic>-dlt`). `DataAccessException`s retry indefinitely and never go to the dead-letter topic: skipping would break ordering.
- `concurrency` at most the partition count; no `@Async` in listeners.

## Topics

Created by `infra/kafka/kafka-topics.sh`, run by the `kafka-topics-init` compose service (`--if-not-exists`, broker auto-create off). Every topic and its dead-letter topic goes in this script, never a `NewTopic` bean. Today: `user.events.v1`, 3 partitions, replication factor 1 (one broker). Partition counts are permanent once a broker holds real data. Transport-level incompatibility (a different key, say) is what a `.v2` topic name is for; payload changes bump `schemaVersion` instead.

## Legacy exception

`energy-usage-events` (legacy ingestion to usage) still uses per-service `NewTopic` beans and per-side event copies with `spring.json.type.mapping`. It disappears when those services are rebuilt onto a shared readings topic.
