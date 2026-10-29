package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.security.Route;
import com.cagritasoz.user_service.testsupport.containers.PostgresTestContainerConfig;
import com.cagritasoz.user_service.testsupport.jwt.LocalJwks;
import com.cagritasoz.user_service.testsupport.jwt.TestTokens;
import io.micrometer.core.instrument.MeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The deletion saga from the user's side up to the promoter, as the application runs it: HTTP requests through the
// real security chain, the real services, a real Postgres and Spring's own scheduler firing the real promoter job
// (nothing here calls promote() by hand). The grace period is 2 seconds and the promoter ticks every 200 ms so the
// test does not wait minutes; the required participants come from the property, which also proves the
// comma-separated list binds into the service.
@SpringBootTest(properties = {
        "app.outbox.relay.interval-ms=86400000",
        "app.outbox.purge.cron=-",
        "app.user.deletion.grace=2s",
        "app.user.deletion.promoter.interval-ms=200",
        "app.user.deletion.keycloak.interval-ms=86400000",
        "app.keycloak.admin.client-secret=test-secret",
        "app.user.deletion.required-participants=devices"
})
@AutoConfigureMockMvc
@Import(PostgresTestContainerConfig.class)
// Closed after the class: a cached context would keep its promoter ticking every 200 ms against the shared database
// while the next test classes seed overdue accounts of their own.
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DeletionPromotionFlowIT {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final String PROBLEM_BASE = "https://home-energy-tracker/problems/";

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
        jdbc.execute("TRUNCATE TABLE outbox_events, users RESTART IDENTITY CASCADE");
    }

    @Test
    void scheduledAccount_whenTheGracePeriodEnds_isPromotedByTheSchedulerAndIsGoneFromTheApi() throws Exception {

        double promotedBefore = meterRegistry.get("user.deletion.promoted").counter().count();

        call(Route.ME_GET).andExpect(status().isOk()); // Provisions the user.
        String email = jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, USER_ID);

        call(Route.ME_DELETE).andExpect(status().isAccepted());
        call(Route.ME_GET).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("DELETION_SCHEDULED"));

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).untilAsserted(() ->
                assertThat(storedStatus()).isEqualTo("DELETING"));

        // The row: scrubbed, with the history of the deletion kept.
        assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, USER_ID))
                .isEqualTo("deleted+" + USER_ID + "@invalid")
                .isNotEqualTo(email);
        assertThat(jdbc.queryForObject("SELECT display_name FROM users WHERE id = ?", String.class, USER_ID)).isEqualTo("Deleted user");
        assertThat(jdbc.queryForObject("""
                SELECT deletion_started_at >= deletion_scheduled_for AND deletion_scheduled_for > deletion_requested_at
                FROM users WHERE id = ?""", Boolean.class, USER_ID)).isTrue();

        // The completion contract of this deletion, and the announcement, in order.
        assertThat(jdbc.queryForList("SELECT participant FROM user_deletion_participants WHERE user_id = ? "
                + "AND acked_at IS NULL ORDER BY participant", String.class, USER_ID)).containsExactly("devices");
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_events ORDER BY seq", String.class))
                .containsExactly("UserRegistered", "UserDeletionScheduled", "UserDeletionStarted");

        // From here on the account is gone on every customer route, with a token that is still valid.
        for (Route route : Route.CUSTOMER) {
            call(route).andExpect(status().isGone()).andExpect(jsonPath("$.type").value(PROBLEM_BASE + "account-deleted"));
        }

        assertThat(meterRegistry.get("user.deletion.promoted").counter().count()).isEqualTo(promotedBefore + 1);

    }

    // The grace period does what it promises: an account canceled in time is never touched by the promoter, however
    // many ticks pass after the original deadline.
    @Test
    void cancelledAccount_isNeverPromoted_evenAfterItsOriginalDeadline() throws Exception {

        call(Route.ME_GET).andExpect(status().isOk());
        call(Route.ME_DELETE).andExpect(status().isAccepted());
        call(Route.ME_CANCEL_DELETION).andExpect(status().isNoContent());

        // Well past the 2 s the deadline would have been, with the promoter ticking every 200 ms.
        await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(storedStatus()).isEqualTo("ACTIVE"));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM user_deletion_participants", Integer.class)).isZero();
        assertThat(jdbc.queryForList("SELECT event_type FROM outbox_events ORDER BY seq", String.class))
                .containsExactly("UserRegistered", "UserDeletionScheduled", "UserDeletionCancelled");
        call(Route.ME_GET).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));

    }

    private ResultActions call(Route route) throws Exception {

        return mockMvc.perform(route.request().header(HttpHeaders.AUTHORIZATION, TestTokens.user(USER_ID).bearer()));

    }

    private String storedStatus() {
        return jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, USER_ID);
    }
}
