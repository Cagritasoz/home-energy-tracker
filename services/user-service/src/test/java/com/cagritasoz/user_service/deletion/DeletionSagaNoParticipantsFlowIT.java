package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.testsupport.containers.PostgresTestContainerConfig;
import com.cagritasoz.user_service.testsupport.web.Route;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The whole saga with no participant services (none exists yet, so the required set is empty): from DELETE /me to
// DELETED by time alone, as the application runs it. Spring's scheduler fires the real promoter, the real Keycloak
// job and the real finalizer; the admin client talks HTTP to WireMock. No Kafka: the relay is parked, and the events
// are read from the outbox table, where they are written.
@Import(PostgresTestContainerConfig.class)
@TestPropertySource(properties = {
        "app.user.deletion.required-participants=",
        "app.outbox.relay.interval-ms=86400000"
})
class DeletionSagaNoParticipantsFlowIT extends DeletionSagaFlowSupport {

    @Test
    void deletingAccount_endsDeleted_afterTheKeycloakDeleteAndTheQuietPeriod() throws Exception {

        UUID user = UUID.randomUUID();
        keycloakAnswersDeletesWith(204);
        double finalizedBefore = counter("user.deletion.finalized");

        provisionAndRequestDeletion(user);

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100)).until(() -> "DELETED".equals(statusOf(user)));

        Map<String, Object> row = jdbc.queryForMap("""
                SELECT version, email, display_name,
                       keycloak_deleted_at IS NOT NULL AS keycloak_stamped,
                       deleted_at >= keycloak_deleted_at + interval '2 seconds' AS waited_the_quiet_period,
                       keycloak_deleted_at >= deletion_started_at AS keycloak_after_promotion
                FROM users WHERE id = ?
                """, user);

        // Four bumps after the insert: schedule, promotion, Keycloak stamp, finalization.
        assertThat(row.get("version")).isEqualTo(4L);
        assertThat(row.get("keycloak_stamped")).isEqualTo(true);
        assertThat(row.get("keycloak_after_promotion")).isEqualTo(true);
        assertThat(row.get("waited_the_quiet_period")).as("the finalizer waits the whole quiet period after the Keycloak delete").isEqualTo(true);
        assertThat(row.get("email")).isEqualTo("deleted+" + user + "@invalid");
        assertThat(row.get("display_name")).isEqualTo("Deleted user");

        // The events, in the order they happened, each with a higher version than the one before.
        assertThat(outboxEventTypes(user)).containsExactly(
                "UserRegistered", "UserDeletionScheduled", "UserDeletionStarted", "UserDeletionFinalized");
        assertThat(jdbc.queryForList("SELECT aggregate_version FROM outbox_events WHERE aggregate_id = ? ORDER BY seq", Long.class, user.toString()))
                .isSorted().doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("SELECT o.occurred_at = u.deleted_at FROM outbox_events o, users u "
                + "WHERE u.id = ? AND o.event_type = 'UserDeletionFinalized'", Boolean.class, user))
                .as("the finalized event carries the row's own deleted_at").isTrue();

        // Keycloak was asked once, and a finished account is not touched again however many ticks pass.
        KEYCLOAK.verify(1, deleteRequestedFor(urlPathEqualTo("/admin/realms/energy-tracker/users/" + user)));
        assertThat(counter("user.deletion.finalized")).isEqualTo(finalizedBefore + 1);
        await().during(Duration.ofSeconds(2)).atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(outboxEventTypes(user)).hasSize(4));

        // The account is gone for the API, with a token that is still valid.
        call(Route.ME_GET, user).andExpect(status().isGone());

    }

    // The finalizer needs the Keycloak delete first: with Keycloak failing, the account stays DELETING however long the
    // quiet period would have been, and is finished by itself once Keycloak is back.
    @Test
    void keycloakDown_theAccountStaysDeletingUntilKeycloakIsBack() throws Exception {

        UUID user = UUID.randomUUID();
        keycloakAnswersDeletesWith(503);

        provisionAndRequestDeletion(user);

        await().atMost(Duration.ofSeconds(30)).pollInterval(Duration.ofMillis(100)).until(() -> "DELETING".equals(statusOf(user)));

        // Longer than the quiet period would be if the clock had started.
        await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(15)).untilAsserted(() -> {

            assertThat(statusOf(user)).isEqualTo("DELETING");
            assertThat(keycloakDeleted(user)).isFalse();

        });

        KEYCLOAK.resetMappings();
        tokenEndpointIssuesTokens();
        keycloakAnswersDeletesWith(204);

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100)).until(() -> "DELETED".equals(statusOf(user)));

        assertThat(outboxEventTypes(user)).endsWith("UserDeletionStarted", "UserDeletionFinalized");

    }

    // Keycloak is gone completely: even the token endpoint does not answer (the connection is reset), so the admin
    // client cannot authenticate, let alone delete. Promotion does not talk to Keycloak, so every account whose grace
    // period ends is still promoted: scrubbed, announced, DELETING. None can be finalized, however long it waits, and
    // all of them are finished by themselves once Keycloak is back.
    @Test
    void keycloakCompletelyDown_everyAccountIsStillPromoted_andAllFinishOnceKeycloakIsBack() throws Exception {

        List<UUID> users = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        KEYCLOAK.stubFor(post(urlPathEqualTo(TOKEN_PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
        KEYCLOAK.stubFor(delete(urlPathMatching(ANY_USER_PATH)).willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));

        for (UUID user : users) {

            provisionAndRequestDeletion(user);

        }

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100)).until(() ->
                users.stream().allMatch(user -> "DELETING".equals(statusOf(user))));

        for (UUID user : users) {

            assertThat(jdbc.queryForObject("SELECT email FROM users WHERE id = ?", String.class, user))
                    .as("scrubbed at promotion although Keycloak is down").isEqualTo("deleted+" + user + "@invalid");
            assertThat(outboxEventTypes(user)).containsExactly("UserRegistered", "UserDeletionScheduled", "UserDeletionStarted");

        }

        // Longer than the quiet period would be if its clock had started: nobody is finalized, nobody is stamped.
        await().during(Duration.ofSeconds(4)).atMost(Duration.ofSeconds(15)).untilAsserted(() -> {

            for (UUID user : users) {

                assertThat(statusOf(user)).isEqualTo("DELETING");
                assertThat(keycloakDeleted(user)).isFalse();

            }

        });

        // Keycloak is back. The request journal is cleared with the stubs, so what is counted below is only what the
        // jobs sent after the recovery.
        KEYCLOAK.resetAll();
        tokenEndpointIssuesTokens();
        keycloakAnswersDeletesWith(204);

        await().atMost(Duration.ofSeconds(60)).pollInterval(Duration.ofMillis(100)).until(() ->
                users.stream().allMatch(user -> "DELETED".equals(statusOf(user))));

        for (UUID user : users) {

            assertThat(outboxEventTypes(user)).endsWith("UserDeletionStarted", "UserDeletionFinalized");
            KEYCLOAK.verify(1, deleteRequestedFor(urlPathEqualTo("/admin/realms/energy-tracker/users/" + user)));

        }

    }
}
