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

## Device events

On `device.events.v1`, producer device-service, key = user id, aggregate type `User` (the events are device-service's answers to what user-service announced). `DeviceEvents` and `DeviceEventType` hold the constants, in `contracts` like the user events.

| Event | `data` |
|---|---|
| `UserDevicesUnclaimed` | `devicesUnclaimed` (how many devices were released, informational, 0 is normal) |

`UserDevicesUnclaimed` is device-service's confirmation, in the deletion saga, that it no longer holds anything personal for the user: its devices are released (the records stay, unowned, claimable again), the user's personal data about them is gone, their credentials are revoked and new claims for the user are refused. It is sent even when there was nothing to release, `aggregateVersion` is null (device-service does not own the user), and `causationId` is the id of the `UserDeletionStarted` it answers. device-service is not built yet: user-service consumes the event from the fixture.

## Contract fixtures

Every event type has one example envelope per schema version in `contracts/src/main/resources/events/<EventType>.v<N>.json`. They live in the `contracts` jar (not in a service's test resources) so any service's tests read them without a test-jar.

- A released fixture is frozen: a breaking change adds `<EventType>.v<N+1>.json` and keeps the old file, because old messages may still be in a topic.
- Each event type has exactly one producer, so its producer test lives in that producer's service and the file is shared; every consumer of the type reads the same file.
- Producer side (built for user-service): `UserEventContractTest` runs the real `OutboxService.record*` and compares the saved payload with the fixture, field for field. Consumer side (built for the confirmation): the listener tests and `DeletionSagaDevicesFlowIT` read `UserDevicesUnclaimed.v1.json`, feed it to the real consumer and assert the effect. The producer test of `UserDevicesUnclaimed` is written in device-service when it is built.

## Consumers

- Read the value as a string and parse the envelope with `data` as a tree (`EventEnvelopeReader`), dispatch on `eventType` (also in the `event_type` header), then convert `data`. Unknown fields are ignored (Jackson 3 default); an event type the consumer does not handle is ignored; an unknown `schemaVersion`, or a message that is not a valid envelope, goes to the dead-letter topic.
- One consumer group per service, named after it. Record-level acknowledgement after the side effect. The `processed_events (event_id, handler)` row is inserted in the same transaction as the side effect.
- Poison goes to `<topic>.dlt` (`DeadLetterPublishingRecoverer` with a destination resolver, because Spring's default is `<topic>-dlt`), unchanged, with the reason in the headers; there is no `ErrorHandlingDeserializer` because values are read as strings and parsed by the listener. `DataAccessException`s retry indefinitely and never go to the dead-letter topic: skipping would break ordering. Any other failure is retried three times and then dead-lettered. Details: `docs/design/02-processed-events-design.md`.
- Every consumer setting that matters is written down in `application.yaml` instead of left to the client's default: `read_committed`, a poll of 100 records, the session, heartbeat and poll timeouts, the classic group protocol with the cooperative sticky assignor (several instances rebalance gently), no topic auto-creation. `KafkaConfigurationTest` pins them.
- `concurrency` at most the partition count; no `@Async` in listeners.

## Topics

Created by `infra/kafka/kafka-topics.sh`, run by the `kafka-topics-init` compose service (`--if-not-exists`, broker auto-create off). Every topic and its dead-letter topic goes in this script, never a `NewTopic` bean in the application (the consumer never creates a topic: `allow.auto.create.topics=false`); the tests declare the topics they consume as `NewTopics` in `KafkaTestContainerConfig`. Today: `user.events.v1` and `device.events.v1`, 3 partitions each, and `device.events.v1.dlt`, 1 partition (the dead-letter topic of a consumed topic is shared by every consumer group of it), replication factor 1 (one broker). Partition counts are permanent once a broker holds real data. Transport-level incompatibility (a different key, say) is what a `.v2` topic name is for; payload changes bump `schemaVersion` instead.

## Legacy exception

`energy-usage-events` (legacy ingestion to usage) still uses per-service `NewTopic` beans and per-side event copies with `spring.json.type.mapping`. It disappears when those services are rebuilt onto a shared readings topic.
