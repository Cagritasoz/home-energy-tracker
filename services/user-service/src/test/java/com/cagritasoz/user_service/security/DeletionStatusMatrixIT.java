package com.cagritasoz.user_service.security;

import com.cagritasoz.user_service.testsupport.LocalJwks;
import com.cagritasoz.user_service.testsupport.PostgresTestContainerConfig;
import com.cagritasoz.user_service.testsupport.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The deletion rules of the HTTP contract, route by route and status by status, through the real security chain,
// the real provisioning interceptor, the real services and a real Postgres. A row is put into each status by hand
// (the jobs that normally do it arrive in later steps), one customer route is called with a valid token, and the
// answer, the event written and the status the row ends in are all checked against the table in the design doc.
@SpringBootTest(properties = {
        "app.outbox.relay.interval-ms=86400000",
        "app.outbox.purge.cron=-"
})
@AutoConfigureMockMvc
@Import(PostgresTestContainerConfig.class)
class DeletionStatusMatrixIT {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final String PROBLEM_BASE = "https://home-energy-tracker/problems/";

    // The states a row can be in, as far as the HTTP contract is concerned. A scheduled row is either still inside
    // its grace period or overdue (the deadline has passed but the promoter has not run yet): the two differ for a cancel.
    enum State { ACTIVE, SCHEDULED, SCHEDULED_OVERDUE, DELETING, DELETED }

    // What a call must produce: the HTTP status, the problem type for an error (null for a success), the event types
    // it writes, and the status the row has afterwards.
    record Expectation(int httpStatus, String problem, List<String> events, String statusAfter) {
    }

    @DynamicPropertySource
    static void sharedJwks(DynamicPropertyRegistry registry) {
        registry.add(SecuritySliceTest.JWK_SET_URI_PROPERTY, LocalJwks.SHARED::url);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void emptyTables() {
        jdbc.execute("TRUNCATE TABLE outbox_events, users RESTART IDENTITY CASCADE");
    }

    static Stream<Arguments> matrix() {

        return Stream.of(
                // GET /users/me: readable through the whole grace period, gone from DELETING on.
                row(Route.ME_GET, State.ACTIVE, new Expectation(200, null, List.of(), "ACTIVE")),
                row(Route.ME_GET, State.SCHEDULED, new Expectation(200, null, List.of(), "DELETION_SCHEDULED")),
                row(Route.ME_GET, State.SCHEDULED_OVERDUE, new Expectation(200, null, List.of(), "DELETION_SCHEDULED")),
                row(Route.ME_GET, State.DELETING, gone("DELETING")),
                row(Route.ME_GET, State.DELETED, gone("DELETED")),

                // PATCH /users/me: read-only while scheduled (409), gone from DELETING on.
                row(Route.ME_PATCH, State.ACTIVE, new Expectation(200, null, List.of(), "ACTIVE")),
                row(Route.ME_PATCH, State.SCHEDULED, new Expectation(409, "deletion-scheduled", List.of(), "DELETION_SCHEDULED")),
                row(Route.ME_PATCH, State.SCHEDULED_OVERDUE, new Expectation(409, "deletion-scheduled", List.of(), "DELETION_SCHEDULED")),
                row(Route.ME_PATCH, State.DELETING, gone("DELETING")),
                row(Route.ME_PATCH, State.DELETED, gone("DELETED")),

                // DELETE /users/me: schedules (and announces it) once; a repeat answers the same date and writes nothing.
                row(Route.ME_DELETE, State.ACTIVE, new Expectation(202, null, List.of("UserDeletionScheduled"), "DELETION_SCHEDULED")),
                row(Route.ME_DELETE, State.SCHEDULED, new Expectation(202, null, List.of(), "DELETION_SCHEDULED")),
                row(Route.ME_DELETE, State.SCHEDULED_OVERDUE, new Expectation(202, null, List.of(), "DELETION_SCHEDULED")),
                row(Route.ME_DELETE, State.DELETING, gone("DELETING")),
                row(Route.ME_DELETE, State.DELETED, gone("DELETED")),

                // POST /users/me/deletion/cancel: works inside the grace period, is a no-op when there is nothing to
                // cancel, is refused once the deadline has passed, and is gone from DELETING on.
                row(Route.ME_CANCEL_DELETION, State.ACTIVE, new Expectation(204, null, List.of(), "ACTIVE")),
                row(Route.ME_CANCEL_DELETION, State.SCHEDULED, new Expectation(204, null, List.of("UserDeletionCancelled"), "ACTIVE")),
                row(Route.ME_CANCEL_DELETION, State.SCHEDULED_OVERDUE,
                        new Expectation(409, "deletion-deadline-passed", List.of(), "DELETION_SCHEDULED")),
                row(Route.ME_CANCEL_DELETION, State.DELETING, gone("DELETING")),
                row(Route.ME_CANCEL_DELETION, State.DELETED, gone("DELETED"))
        );

    }

    private static Arguments row(Route route, State state, Expectation expectation) {
        return Arguments.of(route, state, expectation);
    }

    private static Expectation gone(String statusAfter) {
        return new Expectation(410, "account-deleted", List.of(), statusAfter);
    }

    @ParameterizedTest(name = "{0} while {1}")
    @MethodSource("matrix")
    void everyCustomerRoute_answersAccordingToTheAccountsStatus(Route route, State state, Expectation expected) throws Exception {

        insertUserIn(state);

        ResultActions result = call(route).andExpect(status().is(expected.httpStatus()));

        if (expected.problem() != null) {
            result.andExpect(jsonPath("$.type").value(PROBLEM_BASE + expected.problem()));
        }

        assertThat(outboxEventTypes()).as("events written by the call").isEqualTo(expected.events());
        assertThat(storedStatus()).as("status of the row afterwards").isEqualTo(expected.statusAfter());

    }

    // The deadline is the configured grace after the request, both stamped by the database.
    @Test
    void schedulingDeletion_setsTheDeadlineToTheConfiguredGraceAfterTheRequest() throws Exception {

        insertUserIn(State.ACTIVE);

        call(Route.ME_DELETE)
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("DELETION_SCHEDULED"))
                .andExpect(jsonPath("$.deletionScheduledFor").isNotEmpty());

        // application.yaml sets app.user.deletion.grace to 5 minutes.
        assertThat(jdbc.queryForObject(
                "SELECT deletion_scheduled_for - deletion_requested_at = interval '5 minutes' FROM users WHERE id = ?",
                Boolean.class, USER_ID)).isTrue();

    }

    // A user can change their mind more than once: every transition is announced, in order, and the cleared
    // columns of a cancel never get in the way of the next schedule.
    @Test
    void scheduleCancelSchedule_overHttp_announcesEveryTransitionInOrder() throws Exception {

        call(Route.ME_GET).andExpect(status().isOk()); // First request provisions the user.

        call(Route.ME_DELETE).andExpect(status().isAccepted());
        call(Route.ME_CANCEL_DELETION).andExpect(status().isNoContent());
        call(Route.ME_PATCH).andExpect(status().isOk()); // Writable again.
        call(Route.ME_DELETE).andExpect(status().isAccepted());

        assertThat(outboxEventTypes())
                .containsExactly("UserRegistered", "UserDeletionScheduled", "UserDeletionCancelled", "UserDeletionScheduled");
        assertThat(jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Long.class, USER_ID)).isEqualTo(3L);

    }

    private ResultActions call(Route route) throws Exception {

        return mockMvc.perform(route.request().header(HttpHeaders.AUTHORIZATION, TestTokens.user(USER_ID).bearer()));

    }

    // The row in the given state, with timestamps the table's checks accept. "Now" is the database's clock.
    private void insertUserIn(State state) {

        String requested = state == State.SCHEDULED ? "CURRENT_TIMESTAMP" : "CURRENT_TIMESTAMP - interval '2 days'";
        String scheduledFor = state == State.SCHEDULED ? "CURRENT_TIMESTAMP + interval '1 day'" : "CURRENT_TIMESTAMP - interval '1 day'";

        String sql = switch (state) {

            case ACTIVE -> "INSERT INTO users (id, email, display_name) VALUES (?, 'arthur.morgan@example.com', 'Arthur Morgan')";

            case SCHEDULED, SCHEDULED_OVERDUE -> "INSERT INTO users (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for) "
                    + "VALUES (?, 'arthur.morgan@example.com', 'Arthur Morgan', 'DELETION_SCHEDULED', " + requested + ", " + scheduledFor + ")";

            case DELETING -> "INSERT INTO users (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for, deletion_started_at) "
                    + "VALUES (?, 'deleted+x@invalid', 'Deleted user', 'DELETING', " + requested + ", " + scheduledFor + ", " + scheduledFor + ")";

            case DELETED -> "INSERT INTO users (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for, deletion_started_at, deleted_at) "
                    + "VALUES (?, 'deleted+x@invalid', 'Deleted user', 'DELETED', " + requested + ", " + scheduledFor + ", " + scheduledFor + ", CURRENT_TIMESTAMP)";

        };

        jdbc.update(sql, USER_ID);

    }

    private List<String> outboxEventTypes() {
        return jdbc.queryForList("SELECT event_type FROM outbox_events ORDER BY seq", String.class);
    }

    private String storedStatus() {
        return jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, USER_ID);
    }
}
