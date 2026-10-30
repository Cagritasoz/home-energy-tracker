package com.cagritasoz.user_service.provisioning;

import com.cagritasoz.user_service.deletion.DeletionSchedulingService;
import com.cagritasoz.user_service.keycloak.KeycloakAdmin;
import com.cagritasoz.user_service.keycloak.KeycloakCircuitOpenException;
import com.cagritasoz.user_service.keycloak.KeycloakUnavailableException;
import com.cagritasoz.user_service.testsupport.data.UserFixtures;
import com.cagritasoz.user_service.user.User;
import com.cagritasoz.user_service.user.UserRepository;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith({MockitoExtension.class, OutputCaptureExtension.class})
class OrphanEmailServiceTest {

    private static final UUID HOLDER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final String EMAIL = UserFixtures.ARTHUR_MORGAN_EMAIL;

    @Mock
    private UserRepository userRepository;

    @Mock
    private KeycloakAdmin keycloakAdmin;

    @Mock
    private DeletionSchedulingService schedulingService;

    private SimpleMeterRegistry meters;

    private OrphanEmailService service;

    @BeforeEach
    void createService() {

        meters = new SimpleMeterRegistry();
        service = new OrphanEmailService(userRepository, keycloakAdmin, schedulingService, meters);

    }

    private User holder() {

        return UserFixtures.activeUser().id(HOLDER_ID).build();

    }

    private double released() {
        return meters.get("user.email.orphan.released").counter().count();
    }

    private double live() {
        return meters.get("user.email.collision.live").counter().count();
    }

    // Released between the refused insert and now: nothing to ask Keycloak about, nothing to schedule.
    @Test
    void releaseIfOrphaned_nobodyHoldsTheAddress_reportsNoHolder() {

        when(userRepository.findLiveByEmail(EMAIL)).thenReturn(Optional.empty());

        assertThat(service.releaseIfOrphaned(EMAIL)).isEqualTo(OrphanEmailService.Outcome.NO_HOLDER);

        verifyNoInteractions(keycloakAdmin, schedulingService);

    }

    @Test
    void releaseIfOrphaned_holderHasNoKeycloakUser_schedulesItForImmediateDeletion(CapturedOutput output) {

        when(userRepository.findLiveByEmail(EMAIL)).thenReturn(Optional.of(holder()));
        when(keycloakAdmin.userExists(HOLDER_ID)).thenReturn(false);

        assertThat(service.releaseIfOrphaned(EMAIL)).isEqualTo(OrphanEmailService.Outcome.ORPHAN_RELEASING);

        verify(schedulingService).scheduleForImmediateDeletion(HOLDER_ID);
        assertThat(released()).isEqualTo(1);
        assertThat(live()).isZero();
        assertThat(output.getOut() + output.getErr()).contains(HOLDER_ID.toString()).doesNotContain(EMAIL);

    }

    // The holder exists in Keycloak: our row and Keycloak disagree about the address, which only an operator can
    // resolve. Nothing is changed, and the log names the account, not the address.
    @Test
    void releaseIfOrphaned_holderExistsInKeycloak_reportsItAndChangesNothing(CapturedOutput output) {

        when(userRepository.findLiveByEmail(EMAIL)).thenReturn(Optional.of(holder()));
        when(keycloakAdmin.userExists(HOLDER_ID)).thenReturn(true);

        assertThat(service.releaseIfOrphaned(EMAIL)).isEqualTo(OrphanEmailService.Outcome.HELD_BY_LIVE_ACCOUNT);

        verifyNoInteractions(schedulingService);
        assertThat(live()).isEqualTo(1);
        assertThat(released()).isZero();
        assertThat(output.getOut() + output.getErr()).contains(HOLDER_ID.toString()).doesNotContain(EMAIL);

    }

    // Without an answer from Keycloak nothing can be decided, and above all nothing is scheduled: a down Keycloak must
    // never make every account look like an orphan.
    @ParameterizedTest(name = "circuitOpen={0}")
    @ValueSource(booleans = {false, true})
    void releaseIfOrphaned_keycloakCannotBeAsked_propagatesAndSchedulesNothing(boolean circuitOpen) {

        KeycloakUnavailableException failure = circuitOpen
                ? new KeycloakCircuitOpenException(null)
                : new KeycloakUnavailableException("down", true);
        when(userRepository.findLiveByEmail(EMAIL)).thenReturn(Optional.of(holder()));
        when(keycloakAdmin.userExists(HOLDER_ID)).thenThrow(failure);

        assertThatThrownBy(() -> service.releaseIfOrphaned(EMAIL)).isSameAs(failure);

        verify(schedulingService, never()).scheduleForImmediateDeletion(HOLDER_ID);
        assertThat(released()).isZero();

    }
}
