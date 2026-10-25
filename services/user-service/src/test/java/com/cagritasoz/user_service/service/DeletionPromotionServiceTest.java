package com.cagritasoz.user_service.service;

import com.cagritasoz.user_service.entity.User;
import com.cagritasoz.user_service.model.UserStatus;
import com.cagritasoz.user_service.repository.UserDeletionParticipantRepository;
import com.cagritasoz.user_service.repository.UserRepository;
import com.cagritasoz.user_service.testsupport.UserFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

// No Spring, no database: the repositories answer what the compare-and-set and the insert would, so these tests
// pin the decisions the service makes - what it writes after a successful claim, what it leaves alone after a lost
// one, and which configuration it refuses. What the queries do is proven against Postgres in UserRepositoryIT,
// UserDeletionParticipantRepositoryIT and DeletionPromotionIT.
@ExtendWith(MockitoExtension.class)
class DeletionPromotionServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private UserRepository userRepository;

    @Mock
    private UserDeletionParticipantRepository participantRepository;

    @Mock
    private OutboxService outboxService;

    private DeletionPromotionService service(String... requiredParticipants) {
        return new DeletionPromotionService(userRepository, participantRepository, outboxService, List.of(requiredParticipants));
    }

    private User promotedUser() {
        return UserFixtures.activeUser().id(USER_ID)
                .status(UserStatus.DELETING)
                .version(2L)
                .email("deleted+" + USER_ID + "@invalid")
                .displayName("Deleted user")
                .deletionRequestedAt(Instant.parse("2026-01-31T23:50:00Z"))
                .deletionScheduledFor(Instant.parse("2026-01-31T23:55:00Z"))
                .deletionStartedAt(Instant.parse("2026-01-31T23:55:02Z"))
                .build();
    }

    // --- the required set is validated when the service starts

    @ParameterizedTest
    @ValueSource(strings = {"Devices", "devices ", " devices", "dev ices", "1devices", "_devices", "-devices", "dev.ices", "dev/ices", "dévices", ""})
    void constructor_nameTheTableWouldReject_refusesToStart(String name) {

        // Without this check the first promotion would fail on chk_user_deletion_participants_name, and so would
        // every later one, because the failing account is always first in line.
        assertThatThrownBy(() -> service(name))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.user.deletion.required-participants");

    }

    @Test
    void constructor_nameLongerThanTheTableAllows_refusesToStart() {

        assertThatThrownBy(() -> service("a".repeat(64))).isInstanceOf(IllegalArgumentException.class);

    }

    @Test
    void constructor_nameAtTheLongestLengthTheTableAllows_isAccepted() {

        assertThatCode(() -> service("a".repeat(63))).doesNotThrowAnyException();

    }

    @Test
    void constructor_sameNameTwice_refusesToStart() {

        assertThatThrownBy(() -> service("devices", "usage", "devices"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("devices");

    }

    @Test
    void constructor_emptySetAndValidNames_areAccepted() {

        assertThatCode(this::service).doesNotThrowAnyException();
        assertThatCode(() -> service("devices", "usage-service", "alert_rules", "a1")).doesNotThrowAnyException();

    }

    // --- the work list

    @Test
    void findDue_asksTheRepositoryForTheLimitAndReturnsItsAnswer() {

        List<UUID> due = List.of(USER_ID, UUID.randomUUID());
        when(userRepository.findIdsDueForPromotion(25)).thenReturn(due);

        assertThat(service().findDue(25)).isEqualTo(due);

    }

    // --- promote

    @Test
    void promote_claimLost_writesNothingAndReturnsFalse() {

        when(userRepository.promoteDeletion(USER_ID)).thenReturn(0);

        boolean promoted = service("devices", "usage").promote(USER_ID);

        // A cancel or another promoter got there first: no participant rows, no event, not even a re-read.
        assertThat(promoted).isFalse();
        verify(userRepository).promoteDeletion(USER_ID);
        verifyNoMoreInteractions(userRepository);
        verifyNoInteractions(participantRepository, outboxService);

    }

    @Test
    void promote_claimWon_writesOneRowPerRequiredParticipantAndTheStartedEvent() {

        User promoted = promotedUser();
        when(userRepository.promoteDeletion(USER_ID)).thenReturn(1);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(promoted));

        boolean result = service("devices", "usage").promote(USER_ID);

        assertThat(result).isTrue();

        // The event is built from the row as the database holds it after the update, and written last: nothing
        // announces a deletion before the rows that describe what it waits for.
        InOrder order = inOrder(userRepository, participantRepository, outboxService);
        order.verify(userRepository).promoteDeletion(USER_ID);
        order.verify(userRepository).findById(USER_ID);
        order.verify(participantRepository).insertRequired(USER_ID, "devices");
        order.verify(participantRepository).insertRequired(USER_ID, "usage");
        order.verify(outboxService).recordUserDeletionStarted(promoted);
        verifyNoMoreInteractions(participantRepository, outboxService);

    }

    // While no participant service exists the saga completes by time alone: promotion still starts the deletion
    // and announces it, it just has nobody to wait for.
    @Test
    void promote_emptyRequiredSet_writesNoParticipantRowsButStillAnnouncesTheStart() {

        User promoted = promotedUser();
        when(userRepository.promoteDeletion(USER_ID)).thenReturn(1);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(promoted));

        assertThat(service().promote(USER_ID)).isTrue();

        verifyNoInteractions(participantRepository);
        verify(outboxService).recordUserDeletionStarted(promoted);

    }

    @Test
    void promote_rowMissingAfterTheUpdate_failsInsteadOfPretendingItWorked() {

        when(userRepository.promoteDeletion(USER_ID)).thenReturn(1);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service("devices").promote(USER_ID))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(USER_ID.toString());

        verifyNoInteractions(participantRepository, outboxService);

    }

    // The set a deletion gets must not change under a running service: the configuration is read once, copied.
    @Test
    void promote_listHandedToTheConstructorIsCopied_laterChangesToItDoNotMatter() {

        List<String> configured = new ArrayList<>(List.of("devices"));
        DeletionPromotionService service =
                new DeletionPromotionService(userRepository, participantRepository, outboxService, configured);
        configured.add("usage");
        when(userRepository.promoteDeletion(USER_ID)).thenReturn(1);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(promotedUser()));

        service.promote(USER_ID);

        verify(participantRepository).insertRequired(USER_ID, "devices");
        verify(participantRepository, never()).insertRequired(any(), org.mockito.ArgumentMatchers.eq("usage"));

    }

    @Test
    void promote_participantInsertFails_theErrorPropagatesAndNoEventIsWritten() {

        when(userRepository.promoteDeletion(USER_ID)).thenReturn(1);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(promotedUser()));
        when(participantRepository.insertRequired(any(), anyString())).thenThrow(new IllegalStateException("boom"));

        // The exception is what rolls the whole transaction back (proven against Postgres in DeletionPromotionIT).
        assertThatThrownBy(() -> service("devices").promote(USER_ID)).isInstanceOf(IllegalStateException.class);

        verifyNoInteractions(outboxService);

    }
}
