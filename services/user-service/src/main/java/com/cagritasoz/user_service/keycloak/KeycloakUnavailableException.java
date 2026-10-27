package com.cagritasoz.user_service.keycloak;

// Keycloak could not be asked, or did not answer in a way that settles the question. The message is written by this
// code and never contains anything Keycloak sent back (an error body can quote an email address), only an HTTP status
// or a description of what went wrong.
//
// retryable says whether asking again immediately has a chance: a timeout, a dropped connection, a 5xx and a token
// that was rejected (it may just have expired early) do; a 403 or a wrong client secret does not, and retrying those
// only delays the failure. The circuit breaker counts both kinds.
public class KeycloakUnavailableException extends RuntimeException {

    private final boolean retryable;

    public KeycloakUnavailableException(String message, Throwable cause, boolean retryable) {

        super(message, cause);
        this.retryable = retryable;

    }

    public KeycloakUnavailableException(String message, boolean retryable) {

        this(message, null, retryable);

    }

    public boolean retryable() {

        return retryable;

    }
}
