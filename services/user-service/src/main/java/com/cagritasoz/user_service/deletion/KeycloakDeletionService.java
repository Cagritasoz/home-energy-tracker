package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.keycloak.KeycloakAdmin;
import com.cagritasoz.user_service.user.UserRepository;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

// Deletes the Keycloak user of an account that is DELETING, and records that it is done.
//
// Deliberately NOT @Transactional, and that is the point of this class: the Keycloak call is slow and can fail, and a
// database transaction held open around it would keep a connection (and, if it had locked the row, a lock) for as long
// as Keycloak takes. So the sequence is: read the work list (own short read), call Keycloak (no database involved),
// then one guarded UPDATE (its own short transaction, on the repository method). If the process dies between the call
// and the update, the user is gone from Keycloak and the row is still unstamped: the next tick deletes again, Keycloak
// answers 404, which counts as success, and the stamp is written.
//
// Two instances working through the same list both call Keycloak for the same account (harmless: the delete is
// idempotent) and both try the update; the first gets 1 row, the second 0. The cost of having no row lock is that
// duplicated work, bounded by the batch size.
@Service
public class KeycloakDeletionService {

    private final UserRepository userRepository;

    private final KeycloakAdmin keycloakAdmin;

    public KeycloakDeletionService(UserRepository userRepository, KeycloakAdmin keycloakAdmin) {

        this.userRepository = userRepository;
        this.keycloakAdmin = keycloakAdmin;

    }

    // Oldest deletion first. A plain read: nothing stays locked.
    public List<UUID> findPending(int limit) {

        return userRepository.findIdsPendingKeycloakDeletion(limit);

    }

    // True if this call stamped keycloak_deleted_at; false if the account had already been stamped by another
    // instance, or is no longer DELETING. A Keycloak failure propagates as KeycloakUnavailableException and leaves the
    // row untouched for the next tick.
    public boolean deleteFromKeycloak(UUID id) {

        keycloakAdmin.deleteUser(id);

        return userRepository.markKeycloakDeleted(id) == 1;

    }
}
