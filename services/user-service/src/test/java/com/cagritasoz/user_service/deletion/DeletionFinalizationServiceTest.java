package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.outbox.OutboxService;
import com.cagritasoz.user_service.testsupport.data.UserFixtures;
import com.cagritasoz.user_service.user.User;
import com.cagritasoz.user_service.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// No Spring, no database: the repository answers what the compare-and-set would, so these tests pin the decisions the
// service makes - what it writes after a won claim, what it leaves alone after a lost one, the quiet period it hands
// to the queries, and which configuration it refuses. What the queries do is proven against Postgres in DeletionFinalizationIT.
@ExtendWith(MockitoExtension.class)
class DeletionFinalizationServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private UserRepository userRepository;

    @Mock
    private OutboxService outboxService;

    private DeletionFinalizationService service(Duration quietPeriod) {
        return new DeletionFinalizationService(userRepository, outboxService, quietPeriod);
    }

    // A negative period would finalize an account before its Keycloak user is deleted.
    @ParameterizedTest
    @ValueSource(longs = {-1, -600})
    void constructor_negativeQuietPeriod_refusesToStart(long seconds) {

        assertThatThrownBy(() -> service(Duration.ofSeconds(seconds)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.user.deletion.quiet-period");

    }

    @Test
    void constructor_zeroQuietPeriod_isAllowed() {

        assertThatCode(() -> service(Duration.ZERO)).doesNotThrowAnyException();

    }

    // The queries read the period as a Postgres interval, which accepts the ISO-8601 form Duration prints.
    @Test
    void findReady_passesTheQuietPeriodAsAnIsoDuration() {

        when(userRepository.findIdsReadyForFinalization("PT10M", 25)).thenReturn(List.of(USER_ID));

        assertThat(service(Duration.ofMinutes(10)).findReady(25)).containsExactly(USER_ID);

    }

    @Test
    void finalizeDeletion_wonClaim_readsTheRowAndWritesTheFinalizedEvent() {

        User finalized = UserFixtures.deletedUser().id(USER_ID).build();
        when(userRepository.finalizeDeletion(USER_ID, "PT1M")).thenReturn(1);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(finalized));

        assertThat(service(Duration.ofMinutes(1)).finalizeDeletion(USER_ID)).isTrue();

        // The event is built from the row as the database now holds it, so the read comes after the update.
        InOrder order = inOrder(userRepository, outboxService);
        order.verify(userRepository).finalizeDeletion(USER_ID, "PT1M");
        order.verify(userRepository).findById(USER_ID);
        order.verify(outboxService).recordUserDeletionFinalized(finalized);

    }

    // Another finalizer got there first, or a condition stopped holding: nothing written, nothing read.
    @Test
    void finalizeDeletion_lostClaim_writesNothing() {

        when(userRepository.finalizeDeletion(USER_ID, "PT1M")).thenReturn(0);

        assertThat(service(Duration.ofMinutes(1)).finalizeDeletion(USER_ID)).isFalse();

        verify(userRepository).finalizeDeletion(USER_ID, "PT1M");
        verifyNoInteractions(outboxService);

    }

    // The row vanished between the update and the read: impossible with soft deletes, and loud if it ever happens
    // (the transaction rolls back, so the update is undone with the missing event).
    @Test
    void finalizeDeletion_rowMissingAfterTheUpdate_throws() {

        when(userRepository.finalizeDeletion(USER_ID, "PT1M")).thenReturn(1);
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service(Duration.ofMinutes(1)).finalizeDeletion(USER_ID))
                .isInstanceOf(IllegalStateException.class);

        verify(outboxService, org.mockito.Mockito.never()).recordUserDeletionFinalized(any());

    }
}
