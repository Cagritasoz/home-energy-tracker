package com.cagritasoz.user_service.service;

import com.cagritasoz.contracts.user.UserDeletionCancelledData;
import com.cagritasoz.contracts.user.UserDeletionFinalizedData;
import com.cagritasoz.contracts.user.UserDeletionScheduledData;
import com.cagritasoz.contracts.user.UserDeletionStartedData;
import com.cagritasoz.contracts.user.UserEventType;
import com.cagritasoz.contracts.user.UserEvents;
import com.cagritasoz.contracts.user.UserRegisteredData;
import com.cagritasoz.contracts.user.UserUpdatedData;
import com.cagritasoz.user_service.entity.OutboxEvent;
import com.cagritasoz.user_service.entity.User;
import com.cagritasoz.user_service.model.UserStatus;
import com.cagritasoz.user_service.repository.OutboxRepository;
import com.cagritasoz.user_service.testsupport.UserFixtures;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class OutboxServiceTest {

    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant DELETION_REQUESTED_AT = Instant.parse("2026-01-31T23:50:00Z");
    private static final Instant DELETION_SCHEDULED_FOR = Instant.parse("2026-02-14T23:50:00Z");
    private static final Instant DELETION_STARTED_AT = Instant.parse("2026-02-14T23:50:07Z");
    private static final Instant DELETED_AT = Instant.parse("2026-02-15T00:01:09Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-02-01T00:00:05Z");

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    @Mock
    private OutboxRepository outboxRepository;

    private OutboxService outboxService;

    @BeforeEach
    void setUp() {

        outboxService = new OutboxService(outboxRepository, objectMapper);

    }

    @Test
    void recordUserRegistered_newUser_savesRowBuiltFromOneEnvelope() {

        User user = UserFixtures.activeUser().createdAt(CREATED_AT).updatedAt(CREATED_AT).build();

        outboxService.recordUserRegistered(user);

        OutboxEvent row = savedRow();
        JsonNode payload = payloadOf(row);

        assertRowMatchesUser(row, user, UserEventType.USER_REGISTERED, UserRegisteredData.SCHEMA_VERSION, CREATED_AT);
        assertPayloadMatchesRow(payload, row);
        assertThat(payload.get("data").get("email").asString()).isEqualTo(user.getEmail());
        assertThat(payload.get("data").get("displayName").asString()).isEqualTo(user.getDisplayName());
        assertThat(payload.get("data").get("timezone").asString()).isEqualTo(user.getTimezone());

    }

    @Test
    void recordUserUpdated_changedUser_savesRowWithUpdatedAtAsOccurredAt() {

        User user = UserFixtures.activeUser()
                .email(UserFixtures.LEON_KENNEDY_EMAIL)
                .displayName(UserFixtures.LEON_KENNEDY_NAME)
                .timezone("Europe/Istanbul")
                .version(3L)
                .createdAt(CREATED_AT)
                .updatedAt(UPDATED_AT)
                .build();

        outboxService.recordUserUpdated(user);

        OutboxEvent row = savedRow();
        JsonNode payload = payloadOf(row);

        assertRowMatchesUser(row, user, UserEventType.USER_UPDATED, UserUpdatedData.SCHEMA_VERSION, UPDATED_AT);
        assertPayloadMatchesRow(payload, row);
        assertThat(payload.get("data").get("email").asString()).isEqualTo(UserFixtures.LEON_KENNEDY_EMAIL);
        assertThat(payload.get("data").get("displayName").asString()).isEqualTo(UserFixtures.LEON_KENNEDY_NAME);
        assertThat(payload.get("data").get("timezone").asString()).isEqualTo("Europe/Istanbul");

    }

    // The only deletion event that carries data: the deadline, which consumers need to show or enforce the grace period.
    @Test
    void recordUserDeletionScheduled_scheduledUser_savesRowWithDeletionRequestedAtAndTheDeadline() {

        User user = UserFixtures.activeUser()
                .status(UserStatus.DELETION_SCHEDULED)
                .version(1L)
                .createdAt(CREATED_AT)
                .deletionRequestedAt(DELETION_REQUESTED_AT)
                .deletionScheduledFor(DELETION_SCHEDULED_FOR)
                .updatedAt(DELETION_REQUESTED_AT)
                .build();

        outboxService.recordUserDeletionScheduled(user);

        OutboxEvent row = savedRow();
        JsonNode payload = payloadOf(row);

        assertRowMatchesUser(row, user, UserEventType.USER_DELETION_SCHEDULED, UserDeletionScheduledData.SCHEMA_VERSION, DELETION_REQUESTED_AT);
        assertPayloadMatchesRow(payload, row);
        assertThat(Instant.parse(payload.get("data").get("deletionScheduledFor").asString())).isEqualTo(DELETION_SCHEDULED_FOR);
        assertThat(row.getPayload())
                .doesNotContain(user.getEmail())
                .doesNotContain(user.getDisplayName());

    }

    // The cancel clears the deletion columns, so the only time on the row is updated_at.
    @Test
    void recordUserDeletionCancelled_activeAgainUser_savesRowWithUpdatedAtAndEmptyData() {

        User user = UserFixtures.activeUser()
                .version(2L)
                .createdAt(CREATED_AT)
                .updatedAt(UPDATED_AT)
                .build();

        outboxService.recordUserDeletionCancelled(user);

        OutboxEvent row = savedRow();
        JsonNode payload = payloadOf(row);

        assertRowMatchesUser(row, user, UserEventType.USER_DELETION_CANCELLED, UserDeletionCancelledData.SCHEMA_VERSION, UPDATED_AT);
        assertPayloadMatchesRow(payload, row);
        assertEmptyDataWithoutPersonalFields(payload, row, user);

    }

    // occurredAt is deletion_started_at, not updated_at: updated_at moves on any later update of the row.
    @Test
    void recordUserDeletionStarted_deletingUser_savesRowWithDeletionStartedAtAndEmptyData() {

        User user = UserFixtures.activeUser()
                .status(UserStatus.DELETING)
                .version(2L)
                .createdAt(CREATED_AT)
                .deletionRequestedAt(DELETION_REQUESTED_AT)
                .deletionScheduledFor(DELETION_SCHEDULED_FOR)
                .deletionStartedAt(DELETION_STARTED_AT)
                .updatedAt(UPDATED_AT)
                .build();

        outboxService.recordUserDeletionStarted(user);

        OutboxEvent row = savedRow();
        JsonNode payload = payloadOf(row);

        assertRowMatchesUser(row, user, UserEventType.USER_DELETION_STARTED, UserDeletionStartedData.SCHEMA_VERSION, DELETION_STARTED_AT);
        assertPayloadMatchesRow(payload, row);
        assertEmptyDataWithoutPersonalFields(payload, row, user);

    }

    @Test
    void recordUserDeletionFinalized_deletedUser_savesRowWithDeletedAtAndEmptyData() {

        User user = UserFixtures.deletedUser()
                .version(3L)
                .createdAt(CREATED_AT)
                .deletedAt(DELETED_AT)
                .updatedAt(UPDATED_AT)
                .build();

        outboxService.recordUserDeletionFinalized(user);

        OutboxEvent row = savedRow();
        JsonNode payload = payloadOf(row);

        assertRowMatchesUser(row, user, UserEventType.USER_DELETION_FINALIZED, UserDeletionFinalizedData.SCHEMA_VERSION, DELETED_AT);
        assertPayloadMatchesRow(payload, row);
        assertEmptyDataWithoutPersonalFields(payload, row, user);

    }

    @Test
    void recordUserRegistered_calledTwice_generatesADifferentEventIdEachTime() {

        User user = UserFixtures.activeUser().build();

        outboxService.recordUserRegistered(user);
        outboxService.recordUserRegistered(user);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository, times(2)).save(captor.capture());

        List<OutboxEvent> rows = captor.getAllValues();

        assertThat(rows.get(0).getId()).isNotEqualTo(rows.get(1).getId());
        assertThat(payloadOf(rows.get(0)).get("eventId").asString()).isEqualTo(rows.get(0).getId().toString());
        assertThat(payloadOf(rows.get(1)).get("eventId").asString()).isEqualTo(rows.get(1).getId().toString());

    }

    @Test
    void recordUserRegistered_newRow_leavesDeliveryColumnsAtTheirInitialState() {

        outboxService.recordUserRegistered(UserFixtures.activeUser().build());

        OutboxEvent row = savedRow();

        // The columns below are never set by OutboxService,
        assertThat(row.getSeq()).isNull();
        assertThat(row.getCreatedAt()).isNull();
        assertThat(row.getPublishedAt()).isNull();
        assertThat(row.getAttempts()).isZero();
        assertThat(row.getLastError()).isNull();

    }

    private OutboxEvent savedRow() { // Capture at line 115 for OutboxService.

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        return captor.getValue();

    }

    // "data": {} - these events say who and when through the envelope and must not hand consumers a fresh copy of
    // the user's personal data.
    private void assertEmptyDataWithoutPersonalFields(JsonNode payload, OutboxEvent row, User user) {

        assertThat(payload.get("data").isObject()).isTrue();
        assertThat(payload.get("data").isEmpty()).isTrue();
        assertThat(row.getPayload())
                .doesNotContain(user.getEmail())
                .doesNotContain(user.getDisplayName());

    }

    private JsonNode payloadOf(OutboxEvent row) {

        return objectMapper.readTree(row.getPayload());

    }

    private void assertRowMatchesUser(OutboxEvent row, User user, UserEventType eventType, int schemaVersion, Instant occurredAt) {

        assertThat(row.getId()).isNotNull();
        assertThat(row.getEventType()).isEqualTo(eventType.value());
        assertThat(row.getSchemaVersion()).isEqualTo(schemaVersion);
        assertThat(row.getAggregateType()).isEqualTo(UserEvents.AGGREGATE_TYPE);
        assertThat(row.getAggregateId()).isEqualTo(user.getId().toString());
        assertThat(row.getAggregateVersion()).isEqualTo(user.getVersion());
        assertThat(row.getTopic()).isEqualTo(UserEvents.TOPIC);
        assertThat(row.getOccurredAt()).isEqualTo(occurredAt);
        assertThat(row.getCorrelationId()).isNull();
        assertThat(row.getCausationId()).isNull();

    }

    private void assertPayloadMatchesRow(JsonNode payload, OutboxEvent row) {

        assertThat(payload.isObject()).isTrue();
        assertThat(UUID.fromString(payload.get("eventId").asString())).isEqualTo(row.getId());
        assertThat(payload.get("eventType").asString()).isEqualTo(row.getEventType());
        assertThat(payload.get("schemaVersion").asInt()).isEqualTo(row.getSchemaVersion());
        assertThat(payload.get("aggregateType").asString()).isEqualTo(row.getAggregateType());
        assertThat(payload.get("aggregateId").asString()).isEqualTo(row.getAggregateId());
        assertThat(payload.get("aggregateVersion").asLong()).isEqualTo(row.getAggregateVersion());
        assertThat(Instant.parse(payload.get("occurredAt").asString())).isEqualTo(row.getOccurredAt());
        assertThat(payload.get("producer").asString()).isEqualTo(UserEvents.PRODUCER);
        assertThat(payload.has("data")).isTrue();

    }
}
