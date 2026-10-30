package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.testsupport.jwt.LocalJwks;
import com.cagritasoz.user_service.testsupport.jwt.TestTokens;
import com.cagritasoz.user_service.testsupport.web.Route;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// What the whole-saga tests share: the application with every job of the saga running on a short clock, HTTP through
// the real security chain, a real Postgres, and a WireMock server standing in for Keycloak. A subclass chooses the set of
// participants the deletion waits for and, if it needs Kafka, brings it.
//
// The clock: grace 2 s, every job every 200 ms, quiet period 2 s, so a whole deletion takes seconds. The Keycloak
// circuit breaker is tight (4 calls, open for 1 s) so a Keycloak that comes back is noticed quickly. The context is
// closed after each class: a cached context would keep these fast jobs running against the shared database while
// other classes run.
@SpringBootTest(properties = {
        "app.outbox.purge.cron=-",
        "app.user.deletion.grace=2s",
        "app.user.deletion.promoter.interval-ms=200",
        "app.user.deletion.keycloak.interval-ms=200",
        "app.user.deletion.finalizer.interval-ms=200",
        "app.user.deletion.quiet-period=2s",
        "app.keycloak.admin.client-secret=saga-test-secret",
        "app.keycloak.admin.read-timeout=1s",
        "app.keycloak.admin.retry.wait=20ms",
        "app.keycloak.admin.circuit-breaker.sliding-window-size=4",
        "app.keycloak.admin.circuit-breaker.minimum-calls=4",
        "app.keycloak.admin.circuit-breaker.wait-in-open-state=1s",
        "app.keycloak.admin.circuit-breaker.half-open-calls=1"
})
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
abstract class DeletionSagaFlowSupport {

    static final String TOKEN_PATH = "/realms/energy-tracker/protocol/openid-connect/token";

    static final String ANY_USER_PATH = "/admin/realms/energy-tracker/users/.*";

    // Tell JUnit to register the WireMock extension so that it can manage its lifecycle and run its setup and cleanup callbacks.
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
    MockMvc mockMvc;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    MeterRegistry meterRegistry;

    @BeforeEach
    void emptyTablesAndStubTheToken() {

        jdbc.execute("TRUNCATE TABLE processed_events, outbox_events, user_deletion_participants, users RESTART IDENTITY CASCADE");
        tokenEndpointIssuesTokens();

    }

    void tokenEndpointIssuesTokens() {

        KEYCLOAK.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(okJson("{\"access_token\":\"saga-token\",\"expires_in\":300}")));

    }

    void keycloakAnswersDeletesWith(int status) {

        KEYCLOAK.stubFor(delete(urlPathMatching(ANY_USER_PATH)).willReturn(aResponse().withStatus(status)));

    }

    // The user's first request creates the account, the second asks for the deletion; the rest is the saga.
    void provisionAndRequestDeletion(UUID userId) throws Exception {

        call(Route.ME_GET, userId).andExpect(status().isOk());
        call(Route.ME_DELETE, userId).andExpect(status().isAccepted());

    }

    // Every user has an address of their own, as real users do: the table allows one account per address, and a second
    // user with the first one's address would be taken for a registration that collides with an orphan.
    ResultActions call(Route route, UUID userId) throws Exception {

        String bearer = TestTokens.user(userId).claim("email", "user." + userId + "@example.com").bearer();

        return mockMvc.perform(route.request().header(HttpHeaders.AUTHORIZATION, bearer));

    }

    String statusOf(UUID userId) {

        return jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, userId);

    }

    boolean keycloakDeleted(UUID userId) {

        return Boolean.TRUE.equals(jdbc.queryForObject("SELECT keycloak_deleted_at IS NOT NULL FROM users WHERE id = ?", Boolean.class, userId));

    }

    double counter(String name) {

        return meterRegistry.get(name).counter().count();

    }

    // The events written for the user, in the order they were written.
    List<String> outboxEventTypes(UUID userId) {

        return jdbc.queryForList("SELECT event_type FROM outbox_events WHERE aggregate_id = ? ORDER BY seq", String.class, userId.toString());

    }
}
