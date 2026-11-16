package com.cagritasoz.user_service.keycloak;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.springframework.security.oauth2.client.ClientAuthorizationException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;
import java.util.function.Supplier;

// Keycloak's admin REST API as seen from user-service: a service-account client (user-service-admin, holding only
// manage-users and view-users). The RestClient it is given already carries the access token: Spring Security's OAuth2
// client fetches it with the client-credentials grant, caches it until shortly before it expires and puts it on every
// request (KeycloakAdminConfig builds that client).
//
// Three things guard every call, in this order from the outside in:
//   Retry          - a second attempt after a short pause, only for failures that can be transient (a timeout, a
//                    dropped connection, a 5xx, a rejected token). Safe because both operations are idempotent.
//   CircuitBreaker - after enough failures it refuses calls for a while, so a dead Keycloak is not asked a hundred
//                    times a minute and a caller with a list of accounts can stop early. Every attempt counts, retries
//                    included.
//   Timeouts       - 1 s to connect and 3 s to read (set on the RestClient), so no call waits for long.
// Resilience4j is used programmatically: there is no time limiter, because the calls are blocking and the timeouts
// above already bound them (a time limiter would only abandon a thread that is still waiting).
//
// A 401 from the admin API (the token was revoked, or Keycloak restarted with new keys) makes the OAuth2 client drop
// the cached token and counts as retryable, so the next attempt asks for a fresh one.
//
// Nothing Keycloak sends back is ever put into an exception message or a log: an error body can quote an email
// address. Messages carry the HTTP status or a description of the failure only.
public class KeycloakAdminRestClient implements KeycloakAdmin {

    private final RestClient restClient;

    private final CircuitBreaker circuitBreaker;

    private final Retry retry;

    private final String realm;

    public KeycloakAdminRestClient(RestClient restClient,
                                   CircuitBreaker circuitBreaker,
                                   Retry retry,
                                   String realm) {

        this.restClient = restClient;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.realm = realm;

    }

    @Override
    public void deleteUser(UUID id) {

        call(() -> {

            int status = send(id, true);

            // 204 = deleted now, 404 = not there (anymore): both leave the system in the state asked for.
            if (status != 204 && status != 404) {

                throw failureFor("Keycloak answered " + status + " to a user delete", status);

            }

            return null;

        });

    }

    @Override
    public boolean userExists(UUID id) {

        return call(() -> {

            int status = send(id, false);

            if (status == 200) {

                return true;

            }

            if (status == 404) {

                return false;

            }

            throw failureFor("Keycloak answered " + status + " to a user lookup", status);

        });

    }

    // By default, any Exception thrown out of the supplier is a failure, config sets no recordExceptions, ignoreExceptions or recordFailurePredicate.
    // See circuit breaker config.
    private <T> T call(Supplier<T> attempt) {

        // Register the protected call and decorate the circuit breaker with the retry.
        // "attempt" supplier is the protected call.
        // When relay was designed with a circuit breaker we were reacting to BatchResult values and were manually calling onSuccess, onError
        // Here though the decorated supplier "guarded" wraps the protected call and manually calls onError if it catches an exception
        // if it doesn't catch an exception calls onSuccess. CallNotPermittedException is still caught by us because at the start
        // circuitBreaker.acquirePermission() is attempted which can throw it.
        Supplier<T> guarded = Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, attempt));

        try {

            return guarded.get();

        }
        catch (CallNotPermittedException e) {

            throw new KeycloakCircuitOpenException(e);

        }

    }

    // One HTTP request to /admin/realms/{realm}/users/{id}; returns the status code, whatever it is. What the status
    // means differs per operation, so the callers decide.
    private int send(UUID id, boolean delete) {

        try {

            RestClient.RequestHeadersSpec<?> request = delete
                    ? restClient.delete().uri("/admin/realms/{realm}/users/{id}", realm, id)
                    : restClient.get().uri("/admin/realms/{realm}/users/{id}", realm, id);

            return request.exchange((req, res) -> res.getStatusCode().value());

        }
        catch (ClientAuthorizationException e) {

            // The access token could not be obtained: thrown by the OAuth2 client before the request is sent.
            throw tokenFailure(e);

        }
        catch (ResourceAccessException e) {

            // Timeouts and connection failures; the cause says which, and holds no response data.
            throw new KeycloakUnavailableException("Keycloak could not be reached", e, true);

        }
        catch (RestClientException e) {

            throw new KeycloakUnavailableException("The call to Keycloak failed", e, false);

        }

    }

    // 401 (stale token), 408 (request timeout), 429 (Too many requests)
    // and every 5xx may pass on their own; any other status (403 for a missing role, 400,
    // ...) will not, and a retry would only repeat it.
    private KeycloakUnavailableException failureFor(String message, int status) {

        // Decide if transient, transient = retryable
        boolean transientStatus = status == 401 || status == 408 || status == 429 || status >= 500;

        return new KeycloakUnavailableException(message, transientStatus);

    }

    // A token request that Keycloak answered with an error status never gets here: the token client's status handler
    // (KeycloakAdminConfig) throws the KeycloakUnavailableException itself. What does arrive is a ClientAuthorizationException
    // for the two failures without a status. The OAuth2 client gives both the same error code, invalid_token_response, and
    // quotes the response in its messages, so only what it wraps is read, and neither it nor its message is passed on.
    private KeycloakUnavailableException tokenFailure(ClientAuthorizationException e) {

        for (Throwable cause = e.getCause(); cause != null; cause = cause.getCause()) {

            if (cause instanceof ResourceAccessException unreachable) {

                // A timeout or a refused connection; the cause says which, and holds no response data.
                return new KeycloakUnavailableException("Keycloak could not be reached for a token", unreachable, true);

            }
        }

        // A 200 that is not a token response.
        return new KeycloakUnavailableException("Keycloak's token response could not be read", false);

    }
}
