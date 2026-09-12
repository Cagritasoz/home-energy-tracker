package com.cagritasoz.user_service.entity;

import com.cagritasoz.user_service.model.OutboxAggregateType;
import com.cagritasoz.user_service.model.OutboxEventType;
import jakarta.persistence.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;

// Mirrors V4's outbox_events table exactly. One row = one domain event awaiting (or having
// completed) publish to Kafka, written in the SAME transaction as the users/alert_rules change
// it announces - see V4's header comment for why that matters.
@Entity
@Table(name = "outbox_events")
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "aggregate_type", length = 30, nullable = false)
    @Enumerated(EnumType.STRING)
    private OutboxAggregateType aggregateType;

    // Plain scalar, deliberately NOT a @ManyToOne to User or AlertRule - it can point at either
    // depending on aggregateType, and a USER_DELETED row's referenced user is expected to no
    // longer exist by the time this row is read.
    // A live FK/association here would be actively wrong, not just unneeded.
    @Column(name = "aggregate_id", nullable = false)
    private Long aggregateId;

    // The Kafka message key the relay will send this row with - always the owning user's id,
    // even for ALERT_RULE_* rows (not that rule's own aggregateId). AlertRule cascade-deletes
    // with its user via a DB-level FK invisible to the outbox writer, so a consumer caching
    // alert-rule state has no event other than UserDeleted to learn "this user's rules are gone
    // too" - it depends on that event and the user's earlier AlertRule* events staying strictly
    // ordered. Kafka only guarantees order within a partition, and partition assignment is
    // determined by this key - keying every one of a user's events (their own and their rules')
    // to the SAME value keeps them on the same partition at any partition count, not just today's
    // single partition. Deliberately a separate field from aggregateId: aggregateId identifies
    // which specific row this event is about (needed for traceability - "show me every event for
    // alert rule 47"), partitionKey identifies which ordering group it belongs to - conflating
    // the two would lose the former.
    @Column(name = "partition_key", nullable = false)
    private Long partitionKey;

    @Column(name = "event_type", length = 40, nullable = false)
    @Enumerated(EnumType.STRING)
    private OutboxEventType eventType;

    // The exact JSON sent to Kafka as the message body - opaque here, never parsed or built by
    // this entity (the writer serializes the real event record via Jackson before this entity is
    // even constructed). @JdbcTypeCode(SqlTypes.JSON): Hibernate 6's native jsonb mapping for a
    // plain String, no extra dependency (e.g. hypersistence-utils) needed for something this
    // simple. Unverified against this exact Spring Boot/Hibernate pairing until the app actually
    // starts against the real DB - flagging rather than assuming, same as InfluxDB 3's gap-fill
    // SQL support was flagged elsewhere in this project rather than assumed.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false)
    private String payload;

    // Set once, at insert, in the same transaction as the domain change - never updated after.
    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    // NULL = not yet published, non-null = sent. Deliberately NOT @UpdateTimestamp: that would
    // stamp "now" on any future touch to this row for any reason, but this field means something
    // specific - "when the Kafka publish actually succeeded" - so only the relay sets it,
    // explicitly, exactly once, via a plain setter.
    @Column(name = "published_at")
    private Instant publishedAt;
}
