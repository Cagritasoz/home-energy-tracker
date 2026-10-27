package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.keycloak.KeycloakAdmin;
import com.cagritasoz.user_service.keycloak.KeycloakUnavailableException;
import com.cagritasoz.user_service.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

// Which call comes first and what each outcome of the stamp means. That no transaction is open during the Keycloak call
// needs a real transaction manager, so it is checked in KeycloakDeletionIT.
@ExtendWith(MockitoExtension.class)
class KeycloakDeletionServiceTest {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private UserRepository userRepository;

    @Mock
    private KeycloakAdmin keycloakAdmin;

    private KeycloakDeletionService service() {

        return new KeycloakDeletionService(userRepository, keycloakAdmin);

    }

    @Test
    void findPending_asksTheRepositoryForTheOldestPendingAccounts() {

        when(userRepository.findIdsPendingKeycloakDeletion(20)).thenReturn(List.of(ID));

        assertThat(service().findPending(20)).containsExactly(ID);

    }

    // Keycloak first, the stamp after: the stamp says "the Keycloak user is gone", which is only true once the call
    // has returned.
    @Test
    void deleteFromKeycloak_deletesTheUserThenStampsTheRow() {

        when(userRepository.markKeycloakDeleted(ID)).thenReturn(1);

        boolean stamped = service().deleteFromKeycloak(ID);

        InOrder order = inOrder(keycloakAdmin, userRepository);
        order.verify(keycloakAdmin).deleteUser(ID);
        order.verify(userRepository).markKeycloakDeleted(ID);
        assertThat(stamped).isTrue();

    }

    // Another instance stamped it between our call and our update: Keycloak was told twice (harmless), the row once.
    @Test
    void deleteFromKeycloak_rowAlreadyStamped_returnsFalse() {

        when(userRepository.markKeycloakDeleted(ID)).thenReturn(0);

        assertThat(service().deleteFromKeycloak(ID)).isFalse();

        verify(keycloakAdmin).deleteUser(ID);

    }

    // The stamp must never be written for a user that may still exist: the finaliser would go on to finish an account
    // whose login still works.
    @Test
    void deleteFromKeycloak_keycloakFails_leavesTheRowUnstampedAndPropagates() {

        doThrow(new KeycloakUnavailableException("down", true)).when(keycloakAdmin).deleteUser(ID);

        assertThatThrownBy(() -> service().deleteFromKeycloak(ID))
                .isInstanceOf(KeycloakUnavailableException.class);

        verify(userRepository, never()).markKeycloakDeleted(ID);

    }
}
