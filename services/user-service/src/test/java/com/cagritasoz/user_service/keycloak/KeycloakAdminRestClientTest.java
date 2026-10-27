package com.cagritasoz.user_service.keycloak;

import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The real KeycloakAdminRestClient (real RestClient, real HTTP, real Resilience4j) against a WireMock server standing in
// for Keycloak. No Spring context and no Docker. What these pin down is how the client behaves when Keycloak does what
// Keycloak does, and what it does when Keycloak does not: slow, down, resetting the connection, answering 5xx or 401.
// Whether the real Keycloak answers the way the stubs do is KeycloakAdminClientIT's job.
class KeycloakAdminRestClientTest {

    private static final String REALM = "energy-tracker";

    private static final String CLIENT_ID = "user-service-admin";

    private static final String CLIENT_SECRET = "the-client-secret";

    private static final String TOKEN_PATH = "/realms/" + REALM + "/protocol/openid-connect/token";

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final String USER_PATH = "/admin/realms/" + REALM + "/users/" + USER_ID;

    private static final Duration TOKEN_REFRESH_MARGIN = Duration.ofSeconds(30);

    @RegisterExtension
    static final WireMockExtension KEYCLOAK = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    // A clock the tests move by hand, to step over a token's expiry without waiting for it.
    private static final class MutableClock extends Clock {

        private Instant now = Instant.parse("2026-01-01T00:00:00Z");

        void advance(Duration duration) {

            now = now.plus(duration);

        }

        @Override
        public java.time.ZoneId getZone() {

            return ZoneOffset.UTC;

        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {

            return this;

        }

        @Override
        public Instant instant() {

            return now;

        }
    }

    private final MutableClock clock = new MutableClock();

    // Short timeouts and pauses so the failure cases run in well under a second each; the real values are in
    // application.yaml. The breaker's window is 4 calls with a 50 % threshold, so four straight failures open it.
    private CircuitBreaker breaker = KeycloakAdminConfig.circuitBreaker(4, 4, 50f, Duration.ofSeconds(30), 2);

    private KeycloakAdminRestClient client(int retryAttempts) {

        return new KeycloakAdminRestClient(
                KeycloakAdminConfig.restClient(KEYCLOAK.baseUrl(), Duration.ofMillis(500), Duration.ofMillis(400)),
                breaker,
                KeycloakAdminConfig.retry(retryAttempts, Duration.ofMillis(10)),
                REALM, CLIENT_ID, CLIENT_SECRET, TOKEN_REFRESH_MARGIN, clock);

    }

    private KeycloakAdminRestClient client() {

        return client(2);

    }

    private void tokenEndpointIssues(String token, int expiresInSeconds) {

        KEYCLOAK.stubFor(post(urlPathEqualTo(TOKEN_PATH))
                .willReturn(okJson("{\"access_token\":\"" + token + "\",\"expires_in\":" + expiresInSeconds + ",\"token_type\":\"Bearer\"}")));

    }

    private void userDeleteAnswers(int status) {

        KEYCLOAK.stubFor(delete(urlPathEqualTo(USER_PATH)).willReturn(aResponse().withStatus(status)));

    }

    private void userLookupAnswers(int status) {

        KEYCLOAK.stubFor(get(urlPathEqualTo(USER_PATH)).willReturn(aResponse().withStatus(status)));

    }

    private int deleteRequests() {

        return KEYCLOAK.findAll(deleteRequestedFor(urlPathEqualTo(USER_PATH))).size();

    }

    private int tokenRequests() {

        return KEYCLOAK.findAll(postRequestedFor(urlPathEqualTo(TOKEN_PATH))).size();

    }

    // ---------------------------------------------------------------- the token

    @Test
    void deleteUser_asksForATokenWithTheClientCredentialsAndSendsItAsABearerToken() {

        tokenEndpointIssues("token-1", 300);
        userDeleteAnswers(204);

        client().deleteUser(USER_ID);

        KEYCLOAK.verify(1, postRequestedFor(urlPathEqualTo(TOKEN_PATH))
                .withHeader("Content-Type", containing("application/x-www-form-urlencoded"))
                .withRequestBody(containing("grant_type=client_credentials"))
                .withRequestBody(containing("client_id=" + CLIENT_ID))
                .withRequestBody(containing("client_secret=" + CLIENT_SECRET)));
        KEYCLOAK.verify(1, deleteRequestedFor(urlPathEqualTo(USER_PATH))
                .withHeader("Authorization", equalTo("Bearer token-1")));

    }

    @Test
    void deleteUser_severalCalls_fetchOneTokenAndReuseIt() {

        tokenEndpointIssues("token-1", 300);
        userDeleteAnswers(204);
        userLookupAnswers(200);
        KeycloakAdminRestClient client = client();

        client.deleteUser(USER_ID);
        client.userExists(USER_ID);
        client.deleteUser(USER_ID);

        assertThat(tokenRequests()).isOne();

    }

    // Renewed shortly before it expires (expires_in minus the refresh margin), never after.
    @Test
    void deleteUser_tokenCloseToExpiry_isRenewedBeforeTheNextCall() {

        tokenEndpointIssues("token-1", 100);
        userDeleteAnswers(204);
        KeycloakAdminRestClient client = client();

        client.deleteUser(USER_ID);
        clock.advance(Duration.ofSeconds(69)); // 100 s lifetime - 30 s margin = renewed from 70 s on.
        client.deleteUser(USER_ID);

        assertThat(tokenRequests()).as("still cached at 69 s").isOne();

        tokenEndpointIssues("token-2", 100);
        clock.advance(Duration.ofSeconds(2));
        client.deleteUser(USER_ID);

        assertThat(tokenRequests()).as("renewed at 71 s").isEqualTo(2);
        KEYCLOAK.verify(1, deleteRequestedFor(urlPathEqualTo(USER_PATH)).withHeader("Authorization", equalTo("Bearer token-2")));

    }

    // The admin API rejects the token (revoked, or Keycloak restarted with new keys): the cached token is dropped, the
    // second attempt asks for a new one and succeeds, and the caller sees nothing.
    @Test
    void deleteUser_adminApiRejectsTheToken_getsANewTokenAndSucceeds() {

        KEYCLOAK.stubFor(post(urlPathEqualTo(TOKEN_PATH)).inScenario("tokens").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(okJson("{\"access_token\":\"stale\",\"expires_in\":300}")).willSetStateTo("renewed"));
        KEYCLOAK.stubFor(post(urlPathEqualTo(TOKEN_PATH)).inScenario("tokens").whenScenarioStateIs("renewed")
                .willReturn(okJson("{\"access_token\":\"fresh\",\"expires_in\":300}")));
        KEYCLOAK.stubFor(delete(urlPathEqualTo(USER_PATH)).withHeader("Authorization", equalTo("Bearer stale"))
                .willReturn(aResponse().withStatus(401)));
        KEYCLOAK.stubFor(delete(urlPathEqualTo(USER_PATH)).withHeader("Authorization", equalTo("Bearer fresh"))
                .willReturn(aResponse().withStatus(204)));

        assertThatCode(() -> client().deleteUser(USER_ID)).doesNotThrowAnyException();

        assertThat(tokenRequests()).isEqualTo(2);
        assertThat(deleteRequests()).isEqualTo(2);

    }

    // A wrong secret does not fix itself, so it is not retried and no admin call is made with a token that does not exist.
    @Test
    void deleteUser_tokenRequestRejected_failsAtOnceWithoutRetryingOrCallingTheAdminApi() {

        KEYCLOAK.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(401)
                .withBody("{\"error\":\"unauthorized_client\"}")));

        assertThatThrownBy(() -> client().deleteUser(USER_ID))
                .isInstanceOfSatisfying(KeycloakUnavailableException.class, e -> assertThat(e.retryable()).isFalse());

        assertThat(tokenRequests()).isOne();
        assertThat(deleteRequests()).isZero();

    }

    @Test
    void deleteUser_tokenEndpointFailsWith5xx_isRetriedThenFails() {

        KEYCLOAK.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(503)));

        assertThatThrownBy(() -> client().deleteUser(USER_ID))
                .isInstanceOfSatisfying(KeycloakUnavailableException.class, e -> assertThat(e.retryable()).isTrue());

        assertThat(tokenRequests()).isEqualTo(2);
        assertThat(deleteRequests()).isZero();

    }

    @Test
    void deleteUser_tokenResponseWithoutAnAccessToken_fails() {

        KEYCLOAK.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(okJson("{\"expires_in\":300}")));

        assertThatThrownBy(() -> client().deleteUser(USER_ID))
                .isInstanceOf(KeycloakUnavailableException.class);

    }

    @Test
    void deleteUser_tokenResponseThatIsNotJson_failsWithoutQuotingIt() {

        KEYCLOAK.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(aResponse().withStatus(200).withBody("<html>secret@example.com</html>")));

        assertThatThrownBy(() -> client().deleteUser(USER_ID))
                .isInstanceOf(KeycloakUnavailableException.class)
                .hasMessageNotContaining("secret@example.com");

    }

    @Test
    void constructor_blankClientSecret_failsTheStart() {

        assertThatThrownBy(() -> new KeycloakAdminRestClient(
                KeycloakAdminConfig.restClient(KEYCLOAK.baseUrl(), Duration.ofMillis(500), Duration.ofMillis(400)),
                breaker, KeycloakAdminConfig.retry(2, Duration.ofMillis(10)), REALM, CLIENT_ID, "  ", TOKEN_REFRESH_MARGIN, clock))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("app.keycloak.admin.client-secret");

    }

    // ---------------------------------------------------------------- deleting

    @Test
    void deleteUser_keycloakAnswers204_returnsNormally() {

        tokenEndpointIssues("token-1", 300);
        userDeleteAnswers(204);

        assertThatCode(() -> client().deleteUser(USER_ID)).doesNotThrowAnyException();

        assertThat(deleteRequests()).isOne();

    }

    // The goal is "no such user": if someone else got there first, that is success, and nothing is retried.
    @Test
    void deleteUser_userAlreadyGone_isSuccessAndNotRetried() {

        tokenEndpointIssues("token-1", 300);
        userDeleteAnswers(404);

        assertThatCode(() -> client().deleteUser(USER_ID)).doesNotThrowAnyException();

        assertThat(deleteRequests()).isOne();

    }

    @Test
    void deleteUser_keycloakAnswers5xx_isRetriedOnceThenFailsWithoutQuotingTheBody() {

        tokenEndpointIssues("token-1", 300);
        KEYCLOAK.stubFor(delete(urlPathEqualTo(USER_PATH)).willReturn(aResponse().withStatus(500)
                .withBody("{\"error\":\"could not delete secret@example.com\"}")));

        assertThatThrownBy(() -> client().deleteUser(USER_ID))
                .isInstanceOfSatisfying(KeycloakUnavailableException.class, e -> assertThat(e.retryable()).isTrue())
                .hasMessageContaining("500")
                .hasMessageNotContaining("secret@example.com")
                .hasMessageNotContaining(CLIENT_SECRET);

        assertThat(deleteRequests()).isEqualTo(2);

    }

    @Test
    void deleteUser_failsOnceThenSucceeds_theRetryHidesTheBlip() {

        tokenEndpointIssues("token-1", 300);
        KEYCLOAK.stubFor(delete(urlPathEqualTo(USER_PATH)).inScenario("blip").whenScenarioStateIs(Scenario.STARTED)
                .willReturn(aResponse().withStatus(503)).willSetStateTo("recovered"));
        KEYCLOAK.stubFor(delete(urlPathEqualTo(USER_PATH)).inScenario("blip").whenScenarioStateIs("recovered")
                .willReturn(aResponse().withStatus(204)));

        assertThatCode(() -> client().deleteUser(USER_ID)).doesNotThrowAnyException();

        assertThat(deleteRequests()).isEqualTo(2);

    }

    // A 4xx other than 401, 408 and 429 will not pass on its own (a missing role, a bad request): one attempt only.
    @ParameterizedTest(name = "status={0}")
    @ValueSource(ints = {400, 403, 409})
    void deleteUser_clientErrorThatWillNotPass_isNotRetried(int status) {

        tokenEndpointIssues("token-1", 300);
        userDeleteAnswers(status);

        assertThatThrownBy(() -> client().deleteUser(USER_ID))
                .isInstanceOfSatisfying(KeycloakUnavailableException.class, e -> assertThat(e.retryable()).isFalse());

        assertThat(deleteRequests()).isOne();

    }

    // Keycloak accepts the connection and then hangs: the read timeout (400 ms here, 3 s in production) cuts it off,
    // and so does the retry's second attempt, so the whole call is bounded.
    @Test
    void deleteUser_keycloakTooSlow_timesOutOnEveryAttemptAndFails() {

        tokenEndpointIssues("token-1", 300);
        KEYCLOAK.stubFor(delete(urlPathEqualTo(USER_PATH)).willReturn(aResponse().withStatus(204).withFixedDelay(3000)));

        long started = System.nanoTime();

        assertThatThrownBy(() -> client().deleteUser(USER_ID))
                .isInstanceOfSatisfying(KeycloakUnavailableException.class, e -> assertThat(e.retryable()).isTrue());

        assertThat(Duration.ofNanos(System.nanoTime() - started)).as("two timed-out attempts, not two full responses").isLessThan(Duration.ofSeconds(2));
        assertThat(deleteRequests()).isEqualTo(2);

    }

    @Test
    void deleteUser_connectionReset_failsAfterTheRetry() {

        tokenEndpointIssues("token-1", 300);
        KEYCLOAK.stubFor(delete(urlPathEqualTo(USER_PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        assertThatThrownBy(() -> client().deleteUser(USER_ID))
                .isInstanceOfSatisfying(KeycloakUnavailableException.class, e -> assertThat(e.retryable()).isTrue());

        assertThat(deleteRequests()).isEqualTo(2);

    }

    @Test
    void deleteUser_garbageInsteadOfAnHttpResponse_fails() {

        tokenEndpointIssues("token-1", 300);
        KEYCLOAK.stubFor(delete(urlPathEqualTo(USER_PATH)).willReturn(aResponse().withFault(Fault.RANDOM_DATA_THEN_CLOSE)));

        assertThatThrownBy(() -> client().deleteUser(USER_ID))
                .isInstanceOf(KeycloakUnavailableException.class);

    }

    // Nothing listens on the port: refused at once, not after a long wait.
    @Test
    void deleteUser_keycloakNotRunning_failsFast() throws IOException {

        String deadUrl;

        // A port that was free a moment ago and is closed again.
        try (ServerSocket socket = new ServerSocket(0)) {

            deadUrl = "http://localhost:" + socket.getLocalPort();

        }

        KeycloakAdminRestClient client = new KeycloakAdminRestClient(
                KeycloakAdminConfig.restClient(deadUrl, Duration.ofMillis(500), Duration.ofMillis(400)),
                breaker, KeycloakAdminConfig.retry(2, Duration.ofMillis(10)), REALM, CLIENT_ID, CLIENT_SECRET, TOKEN_REFRESH_MARGIN, clock);

        long started = System.nanoTime();

        assertThatThrownBy(() -> client.deleteUser(USER_ID))
                .isInstanceOfSatisfying(KeycloakUnavailableException.class, e -> assertThat(e.retryable()).isTrue());

        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));

    }

    // ---------------------------------------------------------------- looking up

    @Test
    void userExists_keycloakAnswers200_returnsTrue() {

        tokenEndpointIssues("token-1", 300);
        userLookupAnswers(200);

        assertThat(client().userExists(USER_ID)).isTrue();

        KEYCLOAK.verify(1, getRequestedFor(urlPathEqualTo(USER_PATH)).withHeader("Authorization", equalTo("Bearer token-1")));

    }

    @Test
    void userExists_keycloakAnswers404_returnsFalse() {

        tokenEndpointIssues("token-1", 300);
        userLookupAnswers(404);

        assertThat(client().userExists(USER_ID)).isFalse();

    }

    @Test
    void userExists_keycloakAnswers5xx_isRetriedThenFails() {

        tokenEndpointIssues("token-1", 300);
        userLookupAnswers(502);

        assertThatThrownBy(() -> client().userExists(USER_ID))
                .isInstanceOfSatisfying(KeycloakUnavailableException.class, e -> assertThat(e.retryable()).isTrue());

        assertThat(KEYCLOAK.findAll(getRequestedFor(urlPathEqualTo(USER_PATH)))).hasSize(2);

    }

    @Test
    void userExists_keycloakAnswers403_failsInsteadOfGuessingFalse() {

        tokenEndpointIssues("token-1", 300);
        userLookupAnswers(403);

        // "Not allowed to look" must never read as "does not exist": the caller would delete a live account's row.
        assertThatThrownBy(() -> client().userExists(USER_ID))
                .isInstanceOf(KeycloakUnavailableException.class);

    }

    // ---------------------------------------------------------------- the circuit breaker

    @Test
    void circuitBreaker_afterEnoughFailures_refusesCallsWithoutSendingThem() {

        tokenEndpointIssues("token-1", 300);
        userDeleteAnswers(500);
        KeycloakAdminRestClient client = client(1); // No retry: one failed call = one failed attempt, easy to count.

        for (int i = 0; i < 4; i++) {

            assertThatThrownBy(() -> client.deleteUser(USER_ID)).isInstanceOf(KeycloakUnavailableException.class);

        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        int sentBefore = deleteRequests();

        assertThatThrownBy(() -> client.deleteUser(USER_ID)).isInstanceOf(KeycloakCircuitOpenException.class);
        assertThatThrownBy(() -> client.userExists(USER_ID)).isInstanceOf(KeycloakCircuitOpenException.class);

        assertThat(deleteRequests()).as("a refused call sends nothing").isEqualTo(sentBefore);
        assertThat(tokenRequests()).as("not even a token request").isOne();

    }

    // Asking again at once would be refused again, so the retry does not try.
    @Test
    void circuitBreaker_openCircuit_isNotRetried() {

        breaker.transitionToOpenState();
        KeycloakAdminRestClient client = client(3);

        assertThatThrownBy(() -> client.deleteUser(USER_ID))
                .isInstanceOfSatisfying(KeycloakCircuitOpenException.class, e -> assertThat(e.retryable()).isFalse());

        assertThat(KEYCLOAK.getAllServeEvents()).isEmpty();

    }

    // Half open: the next calls are probes, and enough successes close the circuit again.
    @Test
    void circuitBreaker_probesAfterTheWaitAndClosesWhenKeycloakIsBack() {

        tokenEndpointIssues("token-1", 300);
        userDeleteAnswers(204);
        KeycloakAdminRestClient client = client(1);

        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState(); // What the wait-in-open-state timer does after 30 s.

        client.deleteUser(USER_ID);
        client.deleteUser(USER_ID); // half-open-calls = 2 probes, both fine.

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

    }

    @Test
    void circuitBreaker_failedProbe_opensTheCircuitAgain() {

        tokenEndpointIssues("token-1", 300);
        userDeleteAnswers(500);
        KeycloakAdminRestClient client = client(1);

        breaker.transitionToOpenState();
        breaker.transitionToHalfOpenState();

        assertThatThrownBy(() -> client.deleteUser(USER_ID)).isInstanceOf(KeycloakUnavailableException.class);
        assertThatThrownBy(() -> client.deleteUser(USER_ID)).isInstanceOf(KeycloakUnavailableException.class);

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

    }

    // "No such user" is an answer, not a failure: any number of them leaves the circuit closed.
    @Test
    void circuitBreaker_notFoundAnswers_neverOpenIt() {

        tokenEndpointIssues("token-1", 300);
        userDeleteAnswers(404);
        KeycloakAdminRestClient client = client(1);

        for (int i = 0; i < 20; i++) {

            client.deleteUser(USER_ID);

        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

    }

    // A missing permission is not transient and is not retried, but a client that is refused on every call is just as
    // useless as a down one: the breaker counts it and stops the hammering.
    @Test
    void circuitBreaker_failuresThatAreNotRetried_stillOpenIt() {

        tokenEndpointIssues("token-1", 300);
        userDeleteAnswers(403);
        KeycloakAdminRestClient client = client(2);

        for (int i = 0; i < 4; i++) {

            assertThatThrownBy(() -> client.deleteUser(USER_ID)).isInstanceOf(KeycloakUnavailableException.class);

        }

        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);

    }
}
