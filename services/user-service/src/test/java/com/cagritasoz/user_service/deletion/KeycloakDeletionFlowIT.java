package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.security.Route;
import com.cagritasoz.user_service.testsupport.containers.PostgresTestContainerConfig;
import com.cagritasoz.user_service.testsupport.jwt.LocalJwks;
import com.cagritasoz.user_service.testsupport.jwt.TestTokens;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The deletion saga from the user's request to the Keycloak delete, as the application runs it: HTTP through the real
// security chain, a real Postgres, Spring's own scheduler firing the real promoter and the real Keycloak job, and the
// real admin client talking HTTP to a WireMock server that stands in for Keycloak. Nothing here calls a job by hand.
//
// Timing is shortened (grace 2 s, both jobs every 200 ms, the breaker open for 1 s) so that nothing waits for minutes.
// The context is closed after the class: a cached context would keep its fast jobs running against the shared database
// while other test classes run.
@SpringBootTest(properties = {
        "app.outbox.relay.interval-ms=86400000",
        "app.outbox.purge.cron=-",
        "app.user.deletion.grace=2s",
        "app.user.deletion.promoter.interval-ms=200",
        "app.user.deletion.keycloak.interval-ms=200",
        "app.keycloak.admin.client-secret=flow-test-secret",
        "app.keycloak.admin.read-timeout=1s",
        "app.keycloak.admin.retry.wait=20ms",
        "app.keycloak.admin.circuit-breaker.sliding-window-size=4",
        "app.keycloak.admin.circuit-breaker.minimum-calls=4",
        "app.keycloak.admin.circuit-breaker.wait-in-open-state=1s",
        "app.keycloak.admin.circuit-breaker.half-open-calls=1"
})
@AutoConfigureMockMvc
@Import(PostgresTestContainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class KeycloakDeletionFlowIT {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final String TOKEN_PATH = "/realms/energy-tracker/protocol/openid-connect/token";

    private static final String USER_PATH = "/admin/realms/energy-tracker/users/" + USER_ID;

    private static final String ANY_USER_PATH = "/admin/realms/energy-tracker/users/.*";

    @RegisterExtension
    static final WireMockExtension KEYCLOAK = WireMockExtension.newInstance()
            .options(wireMockConfig().dynamicPort())
            .build();

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {

        registry.add(LocalJwks.JWK_SET_URI_PROPERTY, LocalJwks.SHARED::url);
        registry.add("app.keycloak.admin.base-url", KEYCLOAK::baseUrl);

    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void emptyTablesAndStubTheToken() {

        jdbc.execute("TRUNCATE TABLE outbox_events, users RESTART IDENTITY CASCADE");
        tokenEndpointIssuesTokens();

    }

    private void tokenEndpointIssuesTokens() {

        KEYCLOAK.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(okJson("{\"access_token\":\"flow-token\",\"expires_in\":300}")));

    }

    private void keycloakAnswersDeletesWith(int status) {

        KEYCLOAK.stubFor(delete(urlPathMatching(ANY_USER_PATH)).willReturn(aResponse().withStatus(status)));

    }

    private double counter(String name) {

        return meterRegistry.get(name).counter().count();

    }

    private double circuitState() {

        return meterRegistry.get("keycloak.admin.circuit.state").gauge().value();

    }

    private boolean keycloakDeleted() {

        return jdbc.queryForObject("SELECT keycloak_deleted_at IS NOT NULL FROM users WHERE id = ?", Boolean.class, USER_ID);

    }

    private String storedStatus() {

        return jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, USER_ID);

    }

    // The user asks for the deletion over HTTP and the rest happens by itself.
    private void provisionAndRequestDeletion() throws Exception {

        call(Route.ME_GET).andExpect(status().isOk());
        call(Route.ME_DELETE).andExpect(status().isAccepted());

    }

    private ResultActions call(Route route) throws Exception {

        return mockMvc.perform(route.request().header(HttpHeaders.AUTHORIZATION, TestTokens.user(USER_ID).bearer()));

    }

    @Test
    void deletingAccount_hasItsKeycloakUserDeletedAndTheRowStamped() throws Exception {

        keycloakAnswersDeletesWith(204);
        double deletedBefore = counter("user.deletion.keycloak.deleted");

        // Two other accounts that must never reach Keycloak: one active, one whose grace period is far from over.
        UUID active = UUID.randomUUID();
        UUID scheduledLater = UUID.randomUUID();
        jdbc.update("INSERT INTO users (id, email, display_name) VALUES (?, 'active@example.com', 'Active')", active);
        jdbc.update("""
                INSERT INTO users (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for)
                VALUES (?, 'later@example.com', 'Later', 'DELETION_SCHEDULED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + interval '1 day')
                """, scheduledLater);

        provisionAndRequestDeletion();

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(this::keycloakDeleted);

        // Promoted first, deleted in Keycloak after, stamped at the end; the account stays DELETING (the finaliser is a
        // later step) and never became anything else on the way.
        assertThat(storedStatus()).isEqualTo("DELETING");
        assertThat(jdbc.queryForObject("SELECT keycloak_deleted_at >= deletion_started_at FROM users WHERE id = ?", Boolean.class, USER_ID)).isTrue();
        assertThat(jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Integer.class, USER_ID))
                .as("schedule, promotion, keycloak stamp: three bumps after the insert").isEqualTo(3);

        KEYCLOAK.verify(1, deleteRequestedFor(urlPathEqualTo(USER_PATH)).withHeader("Authorization", equalTo("Bearer flow-token")));
        assertThat(counter("user.deletion.keycloak.deleted")).isEqualTo(deletedBefore + 1);

        // Nobody else was touched, and a finished account is not asked about again however many ticks pass.
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() -> {

            List<LoggedRequest> deletes = KEYCLOAK.findAll(deleteRequestedFor(urlPathMatching(ANY_USER_PATH)));

            assertThat(deletes).extracting(LoggedRequest::getUrl).containsExactly(USER_PATH);

        });

    }

    // The user is already gone from Keycloak (someone deleted it by hand, or an earlier tick got that far): 404 is a
    // success, and the row is stamped like any other.
    @Test
    void keycloakUserAlreadyGone_isTreatedAsDeletedAndStamped() throws Exception {

        keycloakAnswersDeletesWith(404);

        provisionAndRequestDeletion();

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(this::keycloakDeleted);

        assertThat(storedStatus()).isEqualTo("DELETING");

    }

    // Keycloak down never fails the user's request (it was answered 202 long before) and loses nothing: the account
    // stays DELETING and pending, the failures are counted, the breaker opens so the dead Keycloak is not hammered, and
    // when Keycloak is back the very same account is finished without anybody doing anything.
    @Test
    void keycloakDown_theAccountWaitsAndIsFinishedWhenKeycloakIsBack() throws Exception {

        keycloakAnswersDeletesWith(503);
        double failuresBefore = counter("user.deletion.keycloak.failures");

        provisionAndRequestDeletion();

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertThat(storedStatus()).isEqualTo("DELETING"));
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).untilAsserted(() -> {

            assertThat(counter("user.deletion.keycloak.failures")).isGreaterThan(failuresBefore);
            assertThat(circuitState()).as("the circuit breaker opened").isEqualTo(2);

        });

        assertThat(keycloakDeleted()).as("not stamped while Keycloak is failing").isFalse();

        // Keycloak is back.
        KEYCLOAK.resetMappings();
        tokenEndpointIssuesTokens();
        keycloakAnswersDeletesWith(204);

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(this::keycloakDeleted);
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertThat(circuitState()).as("the breaker closed again after a successful probe").isZero());

    }
}
