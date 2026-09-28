package com.cagritasoz.user_service.service;

import com.cagritasoz.contracts.EventEnvelope;
import com.cagritasoz.contracts.user.*;
import com.cagritasoz.user_service.entity.OutboxEvent;
import com.cagritasoz.user_service.entity.User;
import com.cagritasoz.user_service.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxRepository outboxRepository;

    private final ObjectMapper objectMapper;

    // This method MUST be called while a transaction is already active. If there is no transaction, Spring throws an exception.
    @Transactional(propagation = Propagation.MANDATORY)
    public void recordUserRegistered(User user) {

        // The event id is created here, once, and used for both the JSON and the row (see write()).
        // occurredAt and aggregateVersion are copied from the row the database just stamped, so the
        // database stays the owner of time and of the version.
        EventEnvelope<UserRegisteredData> envelope = EventEnvelope.<UserRegisteredData>builder()
                .eventId(UUID.randomUUID())
                .eventType(UserEventType.USER_REGISTERED.value())
                .schemaVersion(UserRegisteredData.SCHEMA_VERSION)
                .aggregateType(UserEvents.AGGREGATE_TYPE)
                .aggregateId(user.getId().toString())
                .aggregateVersion(user.getVersion())
                .occurredAt(user.getCreatedAt())
                .producer(UserEvents.PRODUCER)
                .data(UserRegisteredData.builder()
                        .email(user.getEmail())
                        .displayName(user.getDisplayName())
                        .timezone(user.getTimezone())
                        .build())
                .build();

        write(envelope);

    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordUserUpdated(User user) {

        EventEnvelope<UserUpdatedData> envelope = EventEnvelope.<UserUpdatedData>builder()
                .eventId(UUID.randomUUID())
                .eventType(UserEventType.USER_UPDATED.value())
                .schemaVersion(UserUpdatedData.SCHEMA_VERSION)
                .aggregateType(UserEvents.AGGREGATE_TYPE)
                .aggregateId(user.getId().toString())
                .aggregateVersion(user.getVersion())
                .occurredAt(user.getUpdatedAt())
                .producer(UserEvents.PRODUCER)
                .data(UserUpdatedData.builder()
                        .email(user.getEmail())
                        .displayName(user.getDisplayName())
                        .timezone(user.getTimezone())
                        .build())
                .build();

        write(envelope);

    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void recordUserDeletionRequested(User user) {

        EventEnvelope<UserDeletionRequestedData> envelope = EventEnvelope.<UserDeletionRequestedData>builder()
                .eventId(UUID.randomUUID())
                .eventType(UserEventType.USER_DELETION_REQUESTED.value())
                .schemaVersion(UserDeletionRequestedData.SCHEMA_VERSION)
                .aggregateType(UserEvents.AGGREGATE_TYPE)
                .aggregateId(user.getId().toString())
                .aggregateVersion(user.getVersion())
                .occurredAt(user.getDeletionRequestedAt())
                .producer(UserEvents.PRODUCER)
                .data(UserDeletionRequestedData.builder()
                        .build())
                .build();

        write(envelope);

    }

    // Turns any event envelope into its outbox row: the payload is the envelope as JSON, and the
    // columns are read from the same envelope object, so the two can't disagree.
    // save() is enough (no native query): the row is always new, and an IDENTITY id makes Hibernate run
    // the INSERT right away, so a constraint violation (e.g. a duplicate event id) fails here, inside
    // the caller's transaction, and rolls the whole change back with it.
    private void write(EventEnvelope<?> envelope) {

        OutboxEvent row = OutboxEvent.builder()
                .id(envelope.eventId())
                .aggregateType(envelope.aggregateType())
                .aggregateId(envelope.aggregateId())
                .aggregateVersion(envelope.aggregateVersion())
                .eventType(envelope.eventType())
                .schemaVersion(envelope.schemaVersion())
                .topic(UserEvents.TOPIC)
                .payload(objectMapper.writeValueAsString(envelope)) // Jackson 3: unchecked exception.
                .correlationId(envelope.correlationId())
                .causationId(envelope.causationId())
                .occurredAt(envelope.occurredAt())
                .build();

        outboxRepository.save(row);

    }
}
