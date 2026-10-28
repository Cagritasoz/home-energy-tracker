package com.cagritasoz.user_service.provisioning;

import com.cagritasoz.user_service.security.Route;
import com.cagritasoz.user_service.testsupport.containers.PostgresTestContainerConfig;
import com.cagritasoz.user_service.testsupport.data.UserFixtures;
import com.cagritasoz.user_service.testsupport.jwt.LocalJwks;
import com.cagritasoz.user_service.testsupport.jwt.TestTokens;
import com.github.tomakehurst.wiremock.junit5.WireMockExtension;
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
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// "The address belongs to another account": the whole path as the application runs it. An old account holds the
// address; a person registers again in Keycloak (a new sub, the same address) and makes their first request. The token
// is valid, Keycloak is a WireMock server, the database is a real Postgres, and the real promoter (every 200 ms) is what
// eventually frees the address.
@SpringBootTest(properties = {
        "app.outbox.relay.interval-ms=86400000",
        "app.outbox.purge.cron=-",
        "app.user.deletion.promoter.interval-ms=200",
        "app.user.deletion.keycloak.interval-ms=86400000",
        "app.keycloak.admin.client-secret=flow-test-secret",
        "app.keycloak.admin.read-timeout=1s",
        "app.keycloak.admin.retry.wait=20ms"
})
@AutoConfigureMockMvc
@Import(PostgresTestContainerConfig.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrphanEmailFlowIT {

    private static final UUID OLD_ID = UUID.fromString("aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

    private static final UUID NEW_ID = UUID.fromString("bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbbb");

    private static final String EMAIL = UserFixtures.ARTHUR_MORGAN_EMAIL;

    private static final String TOKEN_PATH = "/realms/energy-tracker/protocol/openid-connect/token";

    private static final String OLD_USER_PATH = "/admin/realms/energy-tracker/users/" + OLD_ID;

    private static final String PROBLEM_BASE = "https://home-energy-tracker/problems/";

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
        KEYCLOAK.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(okJson("{\"access_token\":\"flow-token\",\"expires_in\":300}")));

    }

    private void insertOldAccount(String email) {

        jdbc.update("INSERT INTO users (id, email, display_name) VALUES (?, ?, 'Old Arthur')", OLD_ID, email);

    }

    private void keycloakAnswersTheOldAccountLookupWith(int status) {

        KEYCLOAK.stubFor(get(urlPathEqualTo(OLD_USER_PATH)).willReturn(aResponse().withStatus(status)));

    }

    private ResultActions firstRequestOfTheNewAccount() throws Exception {

        // The token's email is the default test address, the one the old account holds.
        return mockMvc.perform(Route.ME_GET.request().header(HttpHeaders.AUTHORIZATION, TestTokens.user(NEW_ID).bearer()));

    }

    private String statusOfOldAccount() {

        return jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, OLD_ID);

    }

    private Integer rowsOfNewAccount() {

        return jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, NEW_ID);

    }

    private List<String> events() {

        return jdbc.queryForList("SELECT event_type || ':' || aggregate_id FROM outbox_events ORDER BY seq", String.class);

    }

    private double counter(String name) {

        return meterRegistry.get(name).counter().count();

    }

    // The whole point: the old account has no Keycloak user. The first request is told to come back; the old account
    // is scheduled for immediate deletion and announced; the promoter scrubs its address; the retry is provisioned.
    @Test
    void addressHeldByAnOrphan_isReleasedAndTheRetryIsProvisioned() throws Exception {

        insertOldAccount(EMAIL);
        keycloakAnswersTheOldAccountLookupWith(404);
        double releasedBefore = counter("user.email.orphan.released");

        firstRequestOfTheNewAccount()
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "10"))
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "email-being-released"));

        assertThat(rowsOfNewAccount()).as("nothing is created for the new account yet").isZero();
        assertThat(counter("user.email.orphan.released")).isEqualTo(releasedBefore + 1);

        // What the client does with Retry-After: ask again until it works.
        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                firstRequestOfTheNewAccount().andExpect(status().isOk()));

        assertThat(rowsOfNewAccount()).isOne();
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, NEW_ID)).isEqualTo(EMAIL);
        assertThat(statusOfOldAccount()).isEqualTo("DELETING");
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, OLD_ID))
                .isEqualTo("deleted+" + OLD_ID + "@invalid");

        // The old account went through the ordinary saga, and the new one was announced like any other.
        assertThat(events()).containsExactly(
                "UserDeletionScheduled:" + OLD_ID,
                "UserDeletionStarted:" + OLD_ID,
                "UserRegistered:" + NEW_ID);

    }

    // Keycloak says the old account exists: the two systems disagree about whose address this is. Waiting would not
    // help, nothing may be changed, and the new account is told so.
    @Test
    void addressHeldByAnAccountThatExistsInKeycloak_isAConflictAndNothingChanges() throws Exception {

        insertOldAccount(EMAIL);
        keycloakAnswersTheOldAccountLookupWith(200);
        double liveBefore = counter("user.email.collision.live");

        firstRequestOfTheNewAccount()
                .andExpect(status().isConflict())
                .andExpect(header().doesNotExist("Retry-After"))
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "email-already-in-use"));

        assertThat(statusOfOldAccount()).isEqualTo("ACTIVE");
        assertThat(rowsOfNewAccount()).isZero();
        assertThat(events()).isEmpty();
        assertThat(counter("user.email.collision.live")).isEqualTo(liveBefore + 1);

        // Still a conflict a moment later: it does not turn into anything by itself.
        await().during(Duration.ofSeconds(1)).atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                firstRequestOfTheNewAccount().andExpect(status().isConflict()));

        assertThat(statusOfOldAccount()).isEqualTo("ACTIVE");

    }

    // Without an answer from Keycloak nothing is decided: a down Keycloak must never turn a real account into an orphan.
    @Test
    void keycloakDown_isAnUnavailableAnswerAndTheOldAccountIsLeftAlone() throws Exception {

        insertOldAccount(EMAIL);
        keycloakAnswersTheOldAccountLookupWith(503);

        firstRequestOfTheNewAccount()
                .andExpect(status().isServiceUnavailable())
                .andExpect(header().string("Retry-After", "30"))
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "identity-provider-unavailable"));

        assertThat(statusOfOldAccount()).isEqualTo("ACTIVE");
        assertThat(rowsOfNewAccount()).isZero();
        assertThat(events()).isEmpty();

    }

    // The address is compared without regard to case (Keycloak lower-cases, our row may not).
    @Test
    void addressStoredInAnotherCase_isStillRecognisedAsHeld() throws Exception {

        insertOldAccount("Arthur.Morgan@Example.com");
        keycloakAnswersTheOldAccountLookupWith(404);

        firstRequestOfTheNewAccount()
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "email-being-released"));

        assertThat(statusOfOldAccount()).isEqualTo("DELETION_SCHEDULED");

    }

    // An orphan whose owner had scheduled the deletion weeks ahead: the deadline moves to now instead of blocking the
    // address until the grace period would have ended.
    @Test
    void orphanWithALongGracePeriod_isBroughtForwardAndTheRetryIsProvisioned() throws Exception {

        jdbc.update("""
                INSERT INTO users (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for)
                VALUES (?, ?, 'Old Arthur', 'DELETION_SCHEDULED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP + interval '30 days')
                """, OLD_ID, EMAIL);
        keycloakAnswersTheOldAccountLookupWith(404);

        firstRequestOfTheNewAccount().andExpect(status().isServiceUnavailable());

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(300)).untilAsserted(() ->
                firstRequestOfTheNewAccount().andExpect(status().isOk()));

        assertThat(statusOfOldAccount()).isEqualTo("DELETING");

    }

    // The ordinary first request never involves Keycloak: no token request, no lookup.
    @Test
    void ordinaryFirstRequest_neverCallsKeycloak() throws Exception {

        firstRequestOfTheNewAccount().andExpect(status().isOk());

        assertThat(rowsOfNewAccount()).isOne();
        assertThat(KEYCLOAK.getAllServeEvents()).isEmpty();

    }
}
