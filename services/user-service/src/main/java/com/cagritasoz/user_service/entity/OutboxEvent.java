package com.cagritasoz.user_service.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.EqualsAndHashCode;
import lombok.NoArgsConstructor;
import lombok.ToString;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.generator.EventType;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.UUID;

// One row of the transactional outbox (V7): an event that was decided in the same transaction as the
// change it describes, and is published to Kafka later by the relay.
//
// Two kinds of column, and the mapping says which is which:
//  - The EVENT itself (id, aggregate*, event_type, schema_version, topic, payload, correlation_id,
//    causation_id, occurred_at) never changes after the row is written, so those columns are
//    updatable = false: Hibernate will not put them in an UPDATE even if some code calls a setter.
//    Note the flip side - such a setter call is silently ignored, not an error.
//  - The DELIVERY bookkeeping (published_at, attempts, last_error) is the only part the relay ever
//    changes.
//
// No @Version here, unlike User: a single relay is the only writer of a row after it is inserted
// (that is what the advisory lock is for), so there is no lost-update race to guard against.
@Entity
@Table(name = "outbox_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
// @Data would build equals/hashCode from every field, including the big payload, and from a seq that
// is null until the first flush. The event's own id is the natural identity instead - it is what
// consumers dedupe on - so it is the only field used.
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
public class OutboxEvent {

    // Publish order. The database assigns it (GENERATED ALWAYS AS IDENTITY), so Hibernate must leave
    // it out of the INSERT and read the generated value back - which is what IDENTITY does.
    // Not using SEQUENCE/AUTO: that would make Hibernate supply the value itself and Postgres would
    // reject it for a GENERATED ALWAYS column.
    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long seq;

    // The event id. Deliberately NOT generated here: it also has to be written inside the payload,
    // so whoever builds the event creates it once and uses that one value for both. A default here
    // would invite a second, different id.
    @EqualsAndHashCode.Include
    @Column(nullable = false, updatable = false)
    private UUID id;

    @Column(name = "aggregate_type", nullable = false, updatable = false)
    private String aggregateType;

    // The Kafka message key (the user's id, as text).
    @Column(name = "aggregate_id", nullable = false, updatable = false)
    private String aggregateId;

    // Null only for services publishing about aggregates they don't own; user-service always sets it.
    @Column(name = "aggregate_version", updatable = false)
    private Long aggregateVersion;

    @Column(name = "event_type", nullable = false, updatable = false)
    private String eventType;

    // The DB has DEFAULT 1, but Hibernate writes every mapped column explicitly, so the default has
    // to be here too (@Builder.Default, per the entity convention) or the builder would write null.
    @Builder.Default
    @Column(name = "schema_version", nullable = false, updatable = false)
    private int schemaVersion = 1;

    @Column(nullable = false, updatable = false)
    private String topic;

    // The whole event as a JSON object, held as the raw JSON text - the relay sends this string to
    // Kafka as-is. SqlTypes.JSON makes Hibernate bind it as jsonb rather than as text, which is what
    // the column is. Excluded from toString: it carries the user's email and display name, and
    // LoggingAspect logs entities it sees.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, updatable = false)
    @ToString.Exclude
    private String payload;

    @Column(name = "correlation_id", updatable = false)
    private UUID correlationId;

    @Column(name = "causation_id", updatable = false)
    private UUID causationId;

    // Domain time of the change, supplied by the application because it must equal the payload's
    // occurredAt; the caller copies it from the aggregate's own database-stamped timestamp.
    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt;

    // Database-owned, like User.createdAt: DEFAULT CURRENT_TIMESTAMP, never written by Hibernate,
    // read back after the insert.
    @Generated(event = EventType.INSERT)
    @Column(name = "created_at", nullable = false, insertable = false, updatable = false)
    private Instant createdAt;

    // Null = still pending. Set by the relay, only after Kafka confirmed the write.
    @Column(name = "published_at")
    private Instant publishedAt;

    @Builder.Default
    @Column(nullable = false)
    private int attempts = 0;

    @Column(name = "last_error")
    private String lastError;
}
