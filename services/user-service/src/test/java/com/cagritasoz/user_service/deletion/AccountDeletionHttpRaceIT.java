package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.security.Route;
import com.cagritasoz.user_service.testsupport.containers.PostgresTestContainerConfig;
import com.cagritasoz.user_service.testsupport.jwt.LocalJwks;
import com.cagritasoz.user_service.testsupport.jwt.TestTokens;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The customer's requests racing each other through HTTP: the real security chain, the provisioning interceptor, the
// controllers, the services and a real Postgres, with two requests fired at the same moment for the same user. Nothing
// is staged here (DeletionRequestConcurrencyIT stages the same races deterministically at the service level): which
// request wins is up to the scheduler, so the test repeats the race for many users and asserts what must hold whichever
// way each one went - the answers allowed, the events written and their order, and the status the row ends in.
@SpringBootTest(properties = {
        "app.outbox.relay.interval-ms=86400000",
        "app.user.deletion.promoter.interval-ms=86400000",
        "app.user.deletion.keycloak.interval-ms=86400000",
        "app.keycloak.admin.client-secret=test-secret",
        "app.outbox.purge.cron=-"
})
@AutoConfigureMockMvc
@Import(PostgresTestContainerConfig.class)
class AccountDeletionHttpRaceIT {

    private static final int USERS = 25;

    private static final String PROBLEM_BASE = "https://home-energy-tracker/problems/";

    // A response, reduced to what the assertions need.
    private record Answer(int status, String body) {
    }

    @DynamicPropertySource
    static void sharedJwks(DynamicPropertyRegistry registry) {
        registry.add(LocalJwks.JWK_SET_URI_PROPERTY, LocalJwks.SHARED::url);
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbc;

    private final ObjectMapper json = new ObjectMapper();

    private ExecutorService threads;

    @BeforeEach
    void startThreadsAndEmptyTables() {

        threads = Executors.newFixedThreadPool(2);
        jdbc.execute("TRUNCATE TABLE outbox_events, users RESTART IDENTITY CASCADE");

    }

    @AfterEach
    void stopThreads() {

        threads.shutdownNow();

    }

    // Two DELETEs at the same moment: both are accepted with the same date, and the schedule is announced once.
    @Test
    void twoSimultaneousDeletes_bothAreAccepted_withTheSameDate_andTheScheduleIsAnnouncedOnce() throws Exception {

        for (int i = 0; i < USERS; i++) {

            UUID user = UUID.randomUUID();
            provision(user);

            List<Answer> answers = atTheSameMoment(delete(user), delete(user));

            assertThat(answers).extracting(Answer::status).as("both DELETEs for %s", user).containsExactly(202, 202);
            assertThat(deadlineIn(answers.get(0))).isNotNull().isEqualTo(deadlineIn(answers.get(1)));
            assertThat(eventTypes(user)).containsExactly("UserRegistered", "UserDeletionScheduled");
            assertThat(versionOf(user)).isOne();

        }

    }

    // A PATCH and a DELETE at the same moment. Either the PATCH goes first (200, announced before the schedule) or it
    // arrives after the schedule and is refused (409, the account is read-only); the DELETE is accepted either way. It is
    // never a server error, never a change after the schedule, and the versions of the announcements only go up.
    @Test
    void aPatchAndADeleteAtTheSameMoment_neverFail_andLeaveOrderedEvents() throws Exception {

        int patchedFirst = 0;
        int refused = 0;

        for (int i = 0; i < USERS; i++) {

            UUID user = UUID.randomUUID();
            provision(user);

            List<Answer> answers = atTheSameMoment(patch(user, "John Marston"), delete(user));
            Answer patched = answers.get(0);
            Answer deleted = answers.get(1);

            assertThat(deleted.status()).as("DELETE for %s", user).isEqualTo(202);
            assertThat(statusOf(user)).isEqualTo("DELETION_SCHEDULED");

            if (patched.status() == 200) {

                patchedFirst++;
                assertThat(eventTypes(user)).as("PATCH answered 200 for %s", user)
                        .containsExactly("UserRegistered", "UserUpdated", "UserDeletionScheduled");
                assertThat(displayNameOf(user)).isEqualTo("John Marston");

            }
            else {

                refused++;
                assertThat(patched.status()).as("PATCH for %s", user).isEqualTo(409);
                assertThat(problemTypeIn(patched)).isEqualTo(PROBLEM_BASE + "deletion-scheduled");
                assertThat(eventTypes(user)).as("PATCH refused for %s", user)
                        .containsExactly("UserRegistered", "UserDeletionScheduled");
                assertThat(displayNameOf(user)).isEqualTo("Arthur Morgan");

            }

            List<Long> versions = jdbc.queryForList("SELECT aggregate_version FROM outbox_events WHERE aggregate_id = ? ORDER BY seq",
                    Long.class, user.toString());
            assertThat(versions).isSorted().doesNotHaveDuplicates();
            assertThat(versions.getLast()).as("the last announcement carries the row's version").isEqualTo(versionOf(user));

        }

        assertThat(patchedFirst + refused).isEqualTo(USERS);

    }

    // ---- helpers ------------------------------------------------------------------------------------

    // The user's first request creates the account.
    private void provision(UUID user) throws Exception {

        mockMvc.perform(request(Route.ME_GET.request(), user)).andExpect(status().isOk());

    }

    private MockHttpServletRequestBuilder delete(UUID user) {

        return request(Route.ME_DELETE.request(), user);

    }

    private MockHttpServletRequestBuilder patch(UUID user, String displayName) {

        return request(MockMvcRequestBuilders.patch("/api/v1/users/me")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"" + displayName + "\"}"), user);

    }

    // Every user has an address of their own: the table does not allow two accounts with one.
    private static MockHttpServletRequestBuilder request(MockHttpServletRequestBuilder builder, UUID user) {

        return builder.header(HttpHeaders.AUTHORIZATION,
                TestTokens.user(user).claim("email", "user." + user + "@example.com").claim("name", "Arthur Morgan").bearer());

    }

    // Both requests are ready and waiting when the gate opens, so they enter the application together. The answers come
    // back in the order the requests were given.
    private List<Answer> atTheSameMoment(MockHttpServletRequestBuilder first, MockHttpServletRequestBuilder second) throws Exception {

        CountDownLatch gate = new CountDownLatch(1);
        List<Future<Answer>> pending = new ArrayList<>();

        for (MockHttpServletRequestBuilder request : List.of(first, second)) {

            pending.add(threads.submit(() -> {
                gate.await();
                MockHttpServletResponse response = mockMvc.perform(request).andReturn().getResponse();
                return new Answer(response.getStatus(), response.getContentAsString());
            }));

        }

        gate.countDown();

        List<Answer> answers = new ArrayList<>();
        for (Future<Answer> answer : pending) {
            answers.add(answer.get(30, TimeUnit.SECONDS));
        }

        return answers;

    }

    private String deadlineIn(Answer answer) throws Exception {

        JsonNode deadline = json.readTree(answer.body()).get("deletionScheduledFor");

        return deadline == null || deadline.isNull() ? null : deadline.asString();

    }

    private String problemTypeIn(Answer answer) throws Exception {

        return json.readTree(answer.body()).get("type").asString();

    }

    private List<String> eventTypes(UUID user) {
        return jdbc.queryForList("SELECT event_type FROM outbox_events WHERE aggregate_id = ? ORDER BY seq", String.class, user.toString());
    }

    private String statusOf(UUID user) {
        return jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, user);
    }

    private String displayNameOf(UUID user) {
        return jdbc.queryForObject("SELECT display_name FROM users WHERE id = ?", String.class, user);
    }

    private Long versionOf(UUID user) {
        return jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Long.class, user);
    }
}
