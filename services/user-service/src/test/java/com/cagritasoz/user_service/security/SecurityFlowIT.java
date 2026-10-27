package com.cagritasoz.user_service.security;

import com.cagritasoz.user_service.testsupport.containers.PostgresTestContainerConfig;
import com.cagritasoz.user_service.testsupport.jwt.LocalJwks;
import com.cagritasoz.user_service.testsupport.jwt.TestTokens;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(properties = {
        "app.outbox.relay.interval-ms=86400000",
        "app.user.deletion.promoter.interval-ms=86400000",
        "app.user.deletion.keycloak.interval-ms=86400000",
        "app.keycloak.admin.client-secret=test-secret",
        "app.outbox.purge.cron=-"
})
@AutoConfigureMockMvc
@Import(PostgresTestContainerConfig.class)
class SecurityFlowIT {

    private static final UUID CUSTOMER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID OTHER_CUSTOMER_ID = UUID.fromString("44444444-4444-4444-4444-444444444444");

    private static final UUID ADMIN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    @DynamicPropertySource
    static void sharedJwks(DynamicPropertyRegistry registry) {
        registry.add(LocalJwks.JWK_SET_URI_PROPERTY, LocalJwks.SHARED::url);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private MeterRegistry meterRegistry;

    @BeforeEach
    void emptyTables() {
        jdbc.execute("TRUNCATE TABLE outbox_events, user_deletion_participants, users RESTART IDENTITY CASCADE");
    }

    static Stream<Route> customerRoutes() {
        return Route.CUSTOMER.stream();
    }

    static Stream<Route> adminRoutes() {
        return Stream.of(Route.ADMIN_LIST, Route.ADMIN_ONE);
    }

    private ResultActions call(Route route, String bearer) throws Exception {
        var request = route.request();
        if (bearer != null) {
            request.header(HttpHeaders.AUTHORIZATION, bearer);
        }
        return mockMvc.perform(request);
    }

    private double mismatchCount() {
        return meterRegistry.get("user.email.token.mismatch").counter().count();
    }

    private Integer userRows() {
        return jdbc.queryForObject("SELECT count(*) FROM users", Integer.class);
    }

    private List<String> outboxEventTypes() {
        return jdbc.queryForList("SELECT event_type FROM outbox_events ORDER BY seq", String.class);
    }

    private void assertNothingWasWritten() {
        assertThat(userRows()).isZero();
        assertThat(outboxEventTypes()).isEmpty();
    }

    @Test
    void firstCustomerCall_createsExactlyOneUserRowAndOneUserRegisteredEvent() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, TestTokens.user(CUSTOMER_ID).bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CUSTOMER_ID.toString()))
                .andExpect(jsonPath("$.email").value("arthur.morgan@example.com"));

        assertThat(userRows()).isEqualTo(1);
        assertThat(outboxEventTypes()).containsExactly("UserRegistered");
        assertThat(jdbc.queryForObject("SELECT aggregate_id FROM outbox_events", String.class))
                .isEqualTo(CUSTOMER_ID.toString());
    }

    @Test
    void repeatedCustomerCalls_createNothingMore() throws Exception {
        for (int i = 0; i < 3; i++) {
            call(Route.ME_GET, TestTokens.user(CUSTOMER_ID).bearer()).andExpect(status().isOk());
        }

        assertThat(userRows()).isEqualTo(1);
        assertThat(outboxEventTypes()).containsExactly("UserRegistered");
    }

    // A token is a copy of Keycloak's state up to 5 minutes old, so its email is read to create the row and never
    // written into it afterwards: the request still succeeds, the row keeps its email, no event is written, and the
    // divergence is counted.
    @Test
    void changedVerifiedEmailInTheToken_isNotWritten_butCounted() throws Exception {
        call(Route.ME_GET, TestTokens.user(CUSTOMER_ID).bearer()).andExpect(status().isOk());
        double before = mismatchCount();

        call(Route.ME_GET, TestTokens.user(CUSTOMER_ID).claim("email", "new.address@example.com").bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("arthur.morgan@example.com"));

        assertThat(mismatchCount()).isEqualTo(before + 1);
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, CUSTOMER_ID))
                .isEqualTo("arthur.morgan@example.com");
        assertThat(outboxEventTypes()).containsExactly("UserRegistered");
    }

    // An unverified claim says nothing about the account, and Keycloak's lower-casing is no divergence.
    @Test
    void unverifiedOrDifferentlyCasedEmailInTheToken_isNotCounted() throws Exception {
        call(Route.ME_GET, TestTokens.user(CUSTOMER_ID).bearer()).andExpect(status().isOk());
        double before = mismatchCount();

        call(Route.ME_GET, TestTokens.user(CUSTOMER_ID)
                .claim("email", "new.address@example.com").claim("email_verified", false).bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value("arthur.morgan@example.com"));
        call(Route.ME_GET, TestTokens.user(CUSTOMER_ID).claim("email", "ARTHUR.MORGAN@EXAMPLE.COM").bearer())
                .andExpect(status().isOk());

        assertThat(mismatchCount()).isEqualTo(before);
        assertThat(outboxEventTypes()).containsExactly("UserRegistered");
    }

    @Test
    void unverifiedEmailOnTheFirstCall_isRejectedAndNothingIsWritten() throws Exception {
        call(Route.ME_GET, TestTokens.user(CUSTOMER_ID).claim("email_verified", false).bearer())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://home-energy-tracker/problems/email-not-verified"));

        assertNothingWasWritten();
    }

    @Test
    void missingEmailOnTheFirstCall_isRejectedAndNothingIsWritten() throws Exception {
        call(Route.ME_GET, TestTokens.user(CUSTOMER_ID).without("email").bearer())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://home-energy-tracker/problems/missing-identity-claim"));

        assertNothingWasWritten();
    }

    private void assertTheAdminLeftNoTrace(int eventsBefore) {
        assertThat(userRows()).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE id = ?", Integer.class, ADMIN_ID)).isZero();
        assertThat(outboxEventTypes()).hasSize(eventsBefore);
    }

    @Test
    void adminListingUsers_isServedAndNeverBecomesACustomer() throws Exception {
        call(Route.ME_GET, TestTokens.user(CUSTOMER_ID).bearer()).andExpect(status().isOk());
        int eventsBefore = outboxEventTypes().size();

        call(Route.ADMIN_LIST, TestTokens.admin(ADMIN_ID).bearer()).andExpect(status().isOk());

        assertTheAdminLeftNoTrace(eventsBefore);
    }

    @Test
    void adminReadingOneCustomer_isServedAndNeverBecomesACustomer() throws Exception {
        call(Route.ME_GET, TestTokens.user(CUSTOMER_ID).bearer()).andExpect(status().isOk());
        int eventsBefore = outboxEventTypes().size();

        mockMvc.perform(get("/api/v1/admin/users/" + CUSTOMER_ID)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.admin(ADMIN_ID).bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(CUSTOMER_ID.toString()));

        assertTheAdminLeftNoTrace(eventsBefore);
    }

    @Test
    void adminListing_showsCustomersOnly() throws Exception {
        call(Route.ME_GET, TestTokens.user(CUSTOMER_ID).bearer()).andExpect(status().isOk());

        call(Route.ADMIN_LIST, TestTokens.admin(ADMIN_ID).bearer())
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].id").value(CUSTOMER_ID.toString()));
    }

    @ParameterizedTest
    @MethodSource("customerRoutes")
    void adminCallingTheCustomerApi_isForbiddenAndWritesNothing(Route route) throws Exception {
        call(route, TestTokens.admin(ADMIN_ID).bearer()).andExpect(status().isForbidden());

        assertNothingWasWritten();
    }

    @ParameterizedTest
    @MethodSource("adminRoutes")
    void customerCallingTheAdminApi_isForbiddenAndStaysUnprovisioned(Route route) throws Exception {
        call(route, TestTokens.user(CUSTOMER_ID).bearer()).andExpect(status().isForbidden());

        assertNothingWasWritten();
    }

    @ParameterizedTest
    @MethodSource("customerRoutes")
    void requestWithoutAToken_writesNothing(Route route) throws Exception {
        call(route, null).andExpect(status().isUnauthorized());

        assertNothingWasWritten();
    }

    @ParameterizedTest
    @MethodSource("customerRoutes")
    void requestWithAnInvalidToken_writesNothing(Route route) throws Exception {
        call(route, "Bearer " + TestTokens.user(CUSTOMER_ID).signedWith(LocalJwks.ATTACKER_KEY).signed())
                .andExpect(status().isUnauthorized());

        assertNothingWasWritten();
    }

    // The grace period: the account stays readable, writes are refused, a repeat of the request changes nothing.
    // Every route of every status is covered by DeletionStatusMatrixIT; this is the end-to-end happy path.
    @Test
    void deletion_scheduling_keepsTheAccountReadableButReadOnly() throws Exception {
        String bearer = TestTokens.user(CUSTOMER_ID).bearer();
        call(Route.ME_GET, bearer).andExpect(status().isOk());

        call(Route.ME_DELETE, bearer)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("DELETION_SCHEDULED"))
                .andExpect(jsonPath("$.deletionScheduledFor").isNotEmpty());

        call(Route.ME_GET, bearer)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETION_SCHEDULED"));
        call(Route.ME_PATCH, bearer)
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://home-energy-tracker/problems/deletion-scheduled"));
        call(Route.ME_DELETE, bearer).andExpect(status().isAccepted());

        assertThat(outboxEventTypes()).containsExactly("UserRegistered", "UserDeletionScheduled");
    }

    // Once the promoter has started the deletion (simulated here by moving the row to DELETING, as the promoter
    // will) every customer route answers 410 with a token that is still valid: the status comes from the database.
    @Test
    void deletion_afterTheCleanupStarted_blocksEveryCustomerRouteWithAStillValidToken() throws Exception {
        String bearer = TestTokens.user(CUSTOMER_ID).bearer();
        call(Route.ME_GET, bearer).andExpect(status().isOk());
        call(Route.ME_DELETE, bearer).andExpect(status().isAccepted());
        jdbc.update("UPDATE users SET status = 'DELETING', deletion_started_at = CURRENT_TIMESTAMP, version = version + 1 WHERE id = ?",
                CUSTOMER_ID);

        for (Route route : Route.CUSTOMER) {
            call(route, bearer)
                    .andExpect(status().isGone())
                    .andExpect(jsonPath("$.type").value("https://home-energy-tracker/problems/account-deleted"));
        }
        assertThat(outboxEventTypes()).containsExactly("UserRegistered", "UserDeletionScheduled");
    }

    @Test
    void deletion_doesNotBlockTheAdminFromReadingTheAccount() throws Exception {
        call(Route.ME_GET, TestTokens.user(CUSTOMER_ID).bearer()).andExpect(status().isOk());
        call(Route.ME_DELETE, TestTokens.user(CUSTOMER_ID).bearer()).andExpect(status().isAccepted());

        mockMvc.perform(get("/api/v1/admin/users/" + CUSTOMER_ID)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.admin(ADMIN_ID).bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("DELETION_SCHEDULED"))
                .andExpect(jsonPath("$.participants").isEmpty());
    }

    // The admin view shows which services a deletion still waits for. The rows are written by hand here (the promoter
    // that normally writes them arrives in a later step).
    @Test
    void adminView_listsTheParticipantsOfADeletion() throws Exception {
        call(Route.ME_GET, TestTokens.user(CUSTOMER_ID).bearer()).andExpect(status().isOk());
        call(Route.ME_DELETE, TestTokens.user(CUSTOMER_ID).bearer()).andExpect(status().isAccepted());
        jdbc.update("INSERT INTO user_deletion_participants (user_id, participant, acked_at) VALUES (?, 'devices', CURRENT_TIMESTAMP)", CUSTOMER_ID);
        jdbc.update("INSERT INTO user_deletion_participants (user_id, participant) VALUES (?, 'alerts')", CUSTOMER_ID);

        mockMvc.perform(get("/api/v1/admin/users/" + CUSTOMER_ID)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.admin(ADMIN_ID).bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.participants[0].participant").value("alerts"))
                .andExpect(jsonPath("$.participants[0].ackedAt").value((Object) null))
                .andExpect(jsonPath("$.participants[1].participant").value("devices"))
                .andExpect(jsonPath("$.participants[1].ackedAt").isNotEmpty());

        mockMvc.perform(get("/api/v1/admin/users")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.admin(ADMIN_ID).bearer()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].participants.length()").value(2));
    }

    @Test
    void deletedAccount_isGoneEvenWithAFreshValidToken() throws Exception {
        jdbc.update("""
                INSERT INTO users (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for,
                                   deletion_started_at, deleted_at)
                VALUES (?, 'deleted+1@invalid', 'Deleted user', 'DELETED', CURRENT_TIMESTAMP, CURRENT_TIMESTAMP,
                        CURRENT_TIMESTAMP, CURRENT_TIMESTAMP)
                """, OTHER_CUSTOMER_ID);

        call(Route.ME_GET, TestTokens.user(OTHER_CUSTOMER_ID).bearer()).andExpect(status().isGone());

        assertThat(outboxEventTypes()).isEmpty();
    }

    @Test
    void healthEndpoint_isPublicAndShowsNoDetails() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist())
                .andExpect(jsonPath("$.details").doesNotExist());
    }

    @Test
    void healthEndpoint_rejectsAnInvalidBearerTokenEvenThoughItIsPublic() throws Exception {
        mockMvc.perform(get("/actuator/health").header(HttpHeaders.AUTHORIZATION, "Bearer garbage"))
                .andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator", "/actuator/env", "/actuator/info", "/actuator/metrics", "/actuator/beans", "/actuator/prometheus"})
    void otherActuatorEndpoints_areNotPublic(String path) throws Exception {
        mockMvc.perform(get(path)).andExpect(status().isUnauthorized());
    }

    @ParameterizedTest
    @ValueSource(strings = {"/actuator/env", "/actuator/info", "/actuator/metrics", "/actuator/beans", "/actuator/prometheus"})
    void otherActuatorEndpoints_areNotExposedEvenToAdmins(String path) throws Exception {
        mockMvc.perform(get(path).header(HttpHeaders.AUTHORIZATION, TestTokens.admin(ADMIN_ID).bearer()))
                .andExpect(status().isNotFound())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON));
    }
}
