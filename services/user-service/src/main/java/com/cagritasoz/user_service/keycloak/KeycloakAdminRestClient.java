package com.cagritasoz.user_service.keycloak;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Supplier;

// Keycloak's admin REST API as seen from user-service: a service-account client (user-service-admin, holding only
// manage-users and view-users) that fetches its own access token with the client-credentials grant.
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
// The access token is cached until shortly before it expires. A 401 from the admin API (the token was revoked, or
// Keycloak restarted with new keys) throws the cached token away and counts as retryable, so the next attempt asks for
// a fresh one.
//
// Nothing Keycloak sends back is ever put into an exception message or a log: an error body can quote an email
// address. Messages carry the HTTP status or a description of the failure only.
public class KeycloakAdminRestClient implements KeycloakAdmin {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private final RestClient restClient;

    private final CircuitBreaker circuitBreaker;

    private final Retry retry;

    private final String realm;

    private final String clientId;

    private final String clientSecret;

    private final Duration tokenRefreshMargin;

    private final Clock clock;

    // Guarded by "this": one thread at a time fetches a token, the others reuse the result.
    private CachedToken token;

    private record CachedToken(String value, Instant refreshAt) { }

    public KeycloakAdminRestClient(RestClient restClient,
                                   CircuitBreaker circuitBreaker,
                                   Retry retry,
                                   String realm,
                                   String clientId,
                                   String clientSecret,
                                   Duration tokenRefreshMargin,
                                   Clock clock) {

        // A missing secret would otherwise show up as a rejected token on every call, long after the start.
        if (clientSecret == null || clientSecret.isBlank()) {

            throw new IllegalArgumentException("app.keycloak.admin.client-secret must not be blank");

        }

        this.restClient = restClient;
        this.circuitBreaker = circuitBreaker;
        this.retry = retry;
        this.realm = realm;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
        this.tokenRefreshMargin = tokenRefreshMargin;
        this.clock = clock;

    }

    @Override
    public void deleteUser(UUID id) {

        call(() -> {

            int status = send(id, true);

            // 204 = deleted now, 404 = not there (any more): both leave the system in the state asked for.
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

    private <T> T call(Supplier<T> attempt) {

        Supplier<T> guarded = Retry.decorateSupplier(retry, CircuitBreaker.decorateSupplier(circuitBreaker, attempt));

        try {

            return guarded.get();

        }
        catch (CallNotPermittedException e) {

            throw new KeycloakCircuitOpenException(e);

        }

    }

    // One HTTP request to /admin/realms/{realm}/users/{id}; returns the status code, whatever it is. What the status
    // means differs per operation, so the callers decide. A 401 is handled here because it means the token, not the
    // operation.
    private int send(UUID id, boolean delete) {

        String bearer = "Bearer " + accessToken();

        try {

            RestClient.RequestHeadersSpec<?> request = delete
                    ? restClient.delete().uri("/admin/realms/{realm}/users/{id}", realm, id)
                    : restClient.get().uri("/admin/realms/{realm}/users/{id}", realm, id);

            int status = request.header(HttpHeaders.AUTHORIZATION, bearer)
                    .exchange((req, res) -> res.getStatusCode().value());

            if (status == 401) {

                forgetToken();

            }

            return status;

        }
        catch (ResourceAccessException e) {

            // Timeouts and connection failures; the cause says which, and holds no response data.
            throw new KeycloakUnavailableException("Keycloak could not be reached", e, true);

        }
        catch (RestClientException e) {

            throw new KeycloakUnavailableException("The call to Keycloak failed", e, false);

        }

    }

    // 401 (stale token), 408, 429 and every 5xx may pass on their own; any other status (403 for a missing role, 400,
    // ...) will not, and a retry would only repeat it.
    private KeycloakUnavailableException failureFor(String message, int status) {

        boolean transientStatus = status == 401 || status == 408 || status == 429 || status >= 500;

        return new KeycloakUnavailableException(message, transientStatus);

    }

    private synchronized String accessToken() {

        Instant now = clock.instant();

        if (token == null || !now.isBefore(token.refreshAt())) {

            token = fetchToken(now);

        }

        return token.value();

    }

    private synchronized void forgetToken() {

        token = null;

    }

    private CachedToken fetchToken(Instant now) {

        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "client_credentials");
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        String body;

        try {

            body = restClient.post()
                    .uri("/realms/{realm}/protocol/openid-connect/token", realm)
                    .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                    .body(form)
                    .exchange((req, res) -> {

                        int status = res.getStatusCode().value();

                        if (status != 200) {

                            // A wrong secret is 401 and will not fix itself, so it is not retried; a 5xx may.
                            throw new KeycloakUnavailableException(
                                    "Keycloak answered " + status + " to the service account's token request", status >= 500 || status == 429);

                        }

                        return res.bodyTo(String.class);

                    });

        }
        catch (ResourceAccessException e) {

            throw new KeycloakUnavailableException("Keycloak could not be reached for a token", e, true);

        }
        catch (KeycloakUnavailableException e) {

            throw e;

        }
        catch (RestClientException e) {

            throw new KeycloakUnavailableException("The token request to Keycloak failed", e, false);

        }

        try {

            JsonNode json = JSON.readTree(Objects.requireNonNull(body));
            String value = json.path("access_token").asString("");
            long expiresInSeconds = json.path("expires_in").asLong(60);

            if (value.isBlank()) {

                throw new KeycloakUnavailableException("Keycloak's token response held no access token", false);

            }

            return new CachedToken(value, now.plusSeconds(expiresInSeconds).minus(tokenRefreshMargin));

        }
        catch (KeycloakUnavailableException e) {

            throw e;

        }
        catch (RuntimeException e) {

            // Not JSON, or not the shape Keycloak documents. The body is deliberately not part of the message.
            throw new KeycloakUnavailableException("Keycloak's token response could not be read", e, false);

        }

    }
}
