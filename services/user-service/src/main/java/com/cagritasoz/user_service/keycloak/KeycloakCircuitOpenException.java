package com.cagritasoz.user_service.keycloak;

// The circuit breaker is open: recent calls failed, so this one was not even tried. A caller with a list of accounts
// to work through stops at the first of these instead of failing every account in turn.
public class KeycloakCircuitOpenException extends KeycloakUnavailableException {

    public KeycloakCircuitOpenException(Throwable cause) {

        super("The circuit breaker for Keycloak's admin API is open", cause, false);

    }
}
