package com.cagritasoz.user_service.testsupport.keycloak;

import com.cagritasoz.user_service.keycloak.KeycloakAdmin;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

// A KeycloakAdmin whose answers a test writes in advance, for tests that need the code around Keycloak but not HTTP
// (the HTTP side is KeycloakAdminRestClientTest and KeycloakAdminClientIT). Like the real thing, deleting is
// idempotent: a user that is not there is simply gone.
public class ScriptedKeycloakAdmin implements KeycloakAdmin {

    private final Set<UUID> users = Collections.synchronizedSet(new HashSet<>());

    private final List<UUID> deleteCalls = Collections.synchronizedList(new ArrayList<>());

    private final List<UUID> lookupCalls = Collections.synchronizedList(new ArrayList<>());

    // What happens at the start of every deleteUser (a barrier, an assertion about the calling thread, ...).
    private volatile Consumer<UUID> beforeDelete = id -> { };

    // When set, every call fails with it instead of answering.
    private volatile RuntimeException failure;

    public ScriptedKeycloakAdmin hasUser(UUID id) {

        users.add(id);
        return this;

    }

    public ScriptedKeycloakAdmin failingWith(RuntimeException failure) {

        this.failure = failure;
        return this;

    }

    public ScriptedKeycloakAdmin onDelete(Consumer<UUID> beforeDelete) {

        this.beforeDelete = beforeDelete;
        return this;

    }

    public void reset() {

        users.clear();
        deleteCalls.clear();
        lookupCalls.clear();
        beforeDelete = id -> { };
        failure = null;

    }

    public List<UUID> deleteCalls() {

        synchronized (deleteCalls) {

            return List.copyOf(deleteCalls);

        }

    }

    public List<UUID> lookupCalls() {

        synchronized (lookupCalls) {

            return List.copyOf(lookupCalls);

        }

    }

    @Override
    public void deleteUser(UUID id) {

        deleteCalls.add(id);
        beforeDelete.accept(id);

        if (failure != null) {

            throw failure;

        }

        users.remove(id);

    }

    @Override
    public boolean userExists(UUID id) {

        lookupCalls.add(id);

        if (failure != null) {

            throw failure;

        }

        return users.contains(id);

    }
}
