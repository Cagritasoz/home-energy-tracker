package com.cagritasoz.user_service.keycloak;

import java.util.UUID;

// What user-service needs from Keycloak's admin API, and nothing more. An interface so the jobs and services that
// use it can be tested without HTTP, and so the implementation (Keycloak's REST API today) stays replaceable.
//
// Both operations are idempotent, which is what makes them safe to retry and safe to run from two instances at once.
// Neither is ever called from inside a database transaction: a slow Keycloak must not hold a connection.
//
// Every failure that is not a plain answer comes out as a KeycloakUnavailableException (a KeycloakCircuitOpenException
// when the circuit breaker refused the call without trying), whatever the cause: a timeout, a refused connection, a
// 5xx, a rejected token, a missing permission. Callers do not need to tell them apart, they only retry later.
public interface KeycloakAdmin {

    // Deletes the user. Returns normally when the user is gone afterwards, whether this call deleted it or it did not
    // exist anymore (404): the goal is "no such user", and someone else reaching it first is as good.
    void deleteUser(UUID id);

    // Whether a user with this id exists. Used to tell an account whose Keycloak user is really gone (deleted by hand)
    // from one that only looks orphaned.
    boolean userExists(UUID id);
}
