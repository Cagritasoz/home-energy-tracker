package com.cagritasoz.user_service.contract;

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
import com.cagritasoz.user_service.service.OutboxService;
import com.cagritasoz.user_service.testsupport.ContractFixtures;
import com.cagritasoz.user_service.testsupport.UserFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class UserEventContractTest {

    private static final UUID USER_ID = UUID.fromString("6f9619ff-8b86-4d11-b42d-00c04fc964ff");
    private static final Instant CREATED_AT = Instant.parse("2026-01-01T00:00:00Z");
    private static final Instant UPDATED_AT = Instant.parse("2026-02-01T00:00:05Z");
    private static final Instant DELETION_REQUESTED_AT = Instant.parse("2026-01-31T23:50:00Z");
    private static final Instant DELETION_SCHEDULED_FOR = Instant.parse("2026-02-14T23:50:00Z");
    private static final Instant CANCELLED_AT = Instant.parse("2026-02-01T08:15:30Z");
    private static final Instant DELETION_STARTED_AT = Instant.parse("2026-02-14T23:50:07Z");
    private static final Instant DELETED_AT = Instant.parse("2026-02-15T00:01:09Z");

    private final ObjectMapper objectMapper = JsonMapper.builder().build();

    private final OutboxRepository outboxRepository = mock(OutboxRepository.class);

    private final OutboxService outboxService = new OutboxService(outboxRepository, objectMapper);

    @Test
    void recordUserRegistered_payloadEqualsTheFixture() {

        User user = UserFixtures.activeUser()
                .id(USER_ID)
                .version(0L)
                .createdAt(CREATED_AT)
                .updatedAt(CREATED_AT)
                .build();

        outboxService.recordUserRegistered(user);

        assertPayloadEqualsFixture(UserEventType.USER_REGISTERED);

    }

    @Test
    void recordUserUpdated_payloadEqualsTheFixture() {

        User user = UserFixtures.activeUser()
                .id(USER_ID)
                .email(UserFixtures.LEON_KENNEDY_EMAIL)
                .displayName(UserFixtures.LEON_KENNEDY_NAME)
                .timezone("Europe/Istanbul")
                .version(3L)
                .createdAt(CREATED_AT)
                .updatedAt(UPDATED_AT)
                .build();

        outboxService.recordUserUpdated(user);

        assertPayloadEqualsFixture(UserEventType.USER_UPDATED);

    }

    @Test
    void recordUserDeletionScheduled_payloadEqualsTheFixture() {

        User user = UserFixtures.activeUser()
                .id(USER_ID)
                .status(UserStatus.DELETION_SCHEDULED)
                .version(1L)
                .createdAt(CREATED_AT)
                .deletionRequestedAt(DELETION_REQUESTED_AT)
                .deletionScheduledFor(DELETION_SCHEDULED_FOR)
                .updatedAt(DELETION_REQUESTED_AT)
                .build();

        outboxService.recordUserDeletionScheduled(user);

        assertPayloadEqualsFixture(UserEventType.USER_DELETION_SCHEDULED);

    }

    @Test
    void recordUserDeletionCancelled_payloadEqualsTheFixture() {

        User user = UserFixtures.activeUser()
                .id(USER_ID)
                .version(2L)
                .createdAt(CREATED_AT)
                .updatedAt(CANCELLED_AT)
                .build();

        outboxService.recordUserDeletionCancelled(user);

        assertPayloadEqualsFixture(UserEventType.USER_DELETION_CANCELLED);

    }

    @Test
    void recordUserDeletionStarted_payloadEqualsTheFixture() {

        User user = UserFixtures.activeUser()
                .id(USER_ID)
                .status(UserStatus.DELETING)
                .version(2L)
                .createdAt(CREATED_AT)
                .deletionRequestedAt(DELETION_REQUESTED_AT)
                .deletionScheduledFor(DELETION_SCHEDULED_FOR)
                .deletionStartedAt(DELETION_STARTED_AT)
                .updatedAt(DELETION_STARTED_AT)
                .build();

        outboxService.recordUserDeletionStarted(user);

        assertPayloadEqualsFixture(UserEventType.USER_DELETION_STARTED);

    }

    @Test
    void recordUserDeletionFinalized_payloadEqualsTheFixture() {

        User user = UserFixtures.deletedUser()
                .id(USER_ID)
                .version(3L)
                .createdAt(CREATED_AT)
                .deletedAt(DELETED_AT)
                .updatedAt(DELETED_AT)
                .build();

        outboxService.recordUserDeletionFinalized(user);

        assertPayloadEqualsFixture(UserEventType.USER_DELETION_FINALIZED);

    }

    @ParameterizedTest
    @EnumSource(UserEventType.class)
    void fixture_everyEventType_existsAtItsCurrentVersionAndNamesItself(UserEventType eventType) {

        JsonNode fixture = ContractFixtures.load(eventType.value(), schemaVersionOf(eventType));

        assertThat(fixture.get("eventType").asString()).isEqualTo(eventType.value());
        assertThat(fixture.get("schemaVersion").asInt()).isEqualTo(schemaVersionOf(eventType));
        assertThat(fixture.get("aggregateType").asString()).isEqualTo(UserEvents.AGGREGATE_TYPE);
        assertThat(fixture.get("producer").asString()).isEqualTo(UserEvents.PRODUCER);

    }

    private void assertPayloadEqualsFixture(UserEventType eventType) {

        // Capture the row object.
        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        OutboxEvent row = captor.getValue();

        ObjectNode actual = (ObjectNode) objectMapper.readTree(row.getPayload());
        JsonNode expected = ContractFixtures.load(eventType.value(), schemaVersionOf(eventType));

        assertThat(UUID.fromString(actual.get("eventId").asString())).isEqualTo(row.getId());
        actual.put("eventId", expected.get("eventId").asString());

        // Compare the contents.
        assertThat((Object) actual).isEqualTo(expected);

    }

    private static int schemaVersionOf(UserEventType eventType) {

        return switch (eventType) {
            case USER_REGISTERED -> UserRegisteredData.SCHEMA_VERSION;
            case USER_UPDATED -> UserUpdatedData.SCHEMA_VERSION;
            case USER_DELETION_SCHEDULED -> UserDeletionScheduledData.SCHEMA_VERSION;
            case USER_DELETION_CANCELLED -> UserDeletionCancelledData.SCHEMA_VERSION;
            case USER_DELETION_STARTED -> UserDeletionStartedData.SCHEMA_VERSION;
            case USER_DELETION_FINALIZED -> UserDeletionFinalizedData.SCHEMA_VERSION;
        };

    }
}
