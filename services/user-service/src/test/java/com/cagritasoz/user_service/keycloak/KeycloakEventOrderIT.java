package com.cagritasoz.user_service.keycloak;

import com.cagritasoz.user_service.testsupport.containers.ComposeFile;
import com.cagritasoz.user_service.testsupport.keycloak.KeycloakBrowser.Page;
import com.cagritasoz.user_service.testsupport.keycloak.KeycloakBrowser;
import com.cagritasoz.user_service.testsupport.keycloak.KeycloakTestClient.Response;
import com.cagritasoz.user_service.testsupport.keycloak.KeycloakTestClient;
import com.cagritasoz.user_service.testsupport.keycloak.MailpitClient.Mail;
import com.cagritasoz.user_service.testsupport.keycloak.MailpitClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// Which events Keycloak fires, and in which order, for every scenario the application supports; the table in
// infra/keycloak/README.md is this test's output. The events are read from Keycloak's own log: its
// "jboss-logging" listener (on in the realm) writes every event that happens at debug level, whether or not the
// realm stores that type, so this sees the same events as any listener would, including the types the realm does
// not store.
//
// An admin-API action shows up as an admin event "ADMIN_<operation>_<resource>" (Keycloak's own naming is an
// operation type plus a resource type); everything else is a user event named by its type.
//
// A second Keycloak for this class (about 20 seconds), because the event logging is a setting of the container,
// not of the realm, and KeycloakRealmIT should keep running with the settings of docker-compose.yml.
@Testcontainers
class KeycloakEventOrderIT {

    private static final ComposeFile COMPOSE = ComposeFile.load();

    private static final String EMAIL_LISTENER_OPTION = "KC_SPI_EVENTS_LISTENER__EMAIL__INCLUDE_EVENTS";

    private static final String MASTER_ADMIN_PASSWORD = "test-master-admin-password";

    private static final String ADMIN_CLIENT_ID = "user-service-admin";

    private static final String ADMIN_CLIENT_SECRET = "test-user-service-admin-secret";

    private static final String PASSWORD = "Testpassword1";

    private static final String NEW_PASSWORD = "Newpassword123";

    private static final Network NETWORK = Network.newNetwork();

    // type="REGISTER", ... userId="<id>" ...   or   operationType="UPDATE", ... resourceType="USER", resourcePath="users/<id>"
    private static final Pattern EVENT_LINE = Pattern.compile("\\[org\\.keycloak\\.events].*");
    private static final Pattern TYPE = Pattern.compile("(?<![A-Za-z])type=\"([A-Z_]+)\"");
    private static final Pattern OPERATION = Pattern.compile("operationType=\"([A-Z_]+)\"");
    private static final Pattern RESOURCE_TYPE = Pattern.compile("resourceType=\"([A-Z_]+)\"");
    private static final Pattern RESOURCE_PATH = Pattern.compile("resourcePath=\"([^\"]+)\"");
    private static final Pattern USER_ID = Pattern.compile("userId=\"([^\"]+)\"");

    @Container
    private static final GenericContainer<?> MAILPIT = new GenericContainer<>(COMPOSE.image("mailpit"))
            .withNetwork(NETWORK)
            .withNetworkAliases("mailpit")
            .withExposedPorts(8025)
            .waitingFor(Wait.forHttp("/readyz").forPort(8025));

    @Container
    private static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(COMPOSE.image("keycloak"))
            .withNetwork(NETWORK)
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", MASTER_ADMIN_PASSWORD)
            .withEnv("KC_HTTP_ENABLED", "true")
            .withEnv("KC_LOG_LEVEL", "INFO,org.keycloak.events:DEBUG") // The only difference from KeycloakRealmIT's container.
            .withEnv("USER_SERVICE_ADMIN_CLIENT_SECRET", ADMIN_CLIENT_SECRET)
            .withEnv(EMAIL_LISTENER_OPTION, COMPOSE.environment("keycloak", EMAIL_LISTENER_OPTION))
            .withCopyFileToContainer(MountableFile.forHostPath(ComposeFile.repoFile("infra/keycloak/realm-energy.json")),
                    "/opt/keycloak/data/import/realm-energy.json")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/energy-tracker").forPort(8080).withStartupTimeout(Duration.ofMinutes(3)));

    private static KeycloakTestClient keycloak;

    private static MailpitClient mailpit;

    @BeforeAll
    static void connect() {

        keycloak = new KeycloakTestClient("http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080));
        mailpit = new MailpitClient("http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025));

    }

    // ---------------------------------------------------------------- registration and passwords

    @Test
    void registration_firesRegisterVerifyThenThePasswordEvents() {

        String email = newEmail();
        KeycloakBrowser browser = new KeycloakBrowser(keycloak);

        Page afterSubmit = browser.submit(browser.openRegistration(), Map.of("email", email));
        assertThat(afterSubmit.text()).contains("verify your email address");

        Page passwordForm = browser.openMailedLink(mailpit.firstLink(awaitMail(email, "Verify email")));
        browser.submit(passwordForm, Map.of("password-new", NEW_PASSWORD, "password-confirm", NEW_PASSWORD));

        String id = keycloak.findUser(master(), email).path("id").asString();

        // The first password is an UPDATE_PASSWORD event, identical to the one of a later change: this is why the
        // email listener cannot be told to skip it. SEND_VERIFY_EMAIL is the event of the "Verify email" mail.
        assertEvents(id, 0, "REGISTER", "SEND_VERIFY_EMAIL", "VERIFY_EMAIL", "UPDATE_PASSWORD", "UPDATE_CREDENTIAL", "LOGIN");

        // The realm stores only the types in its enabledEventTypes list, so two of the six are logged but not stored.
        List<String> stored = new ArrayList<>();
        for (JsonNode event : keycloak.get(keycloak.adminUrl() + "/events?user=" + id + "&max=50", master()).json()) {
            stored.add(event.path("type").asString());
        }
        assertThat(stored).containsExactlyInAnyOrder("REGISTER", "VERIFY_EMAIL", "UPDATE_PASSWORD", "LOGIN");

    }

    @Test
    void forgotPassword_firesSendResetThenTheUpdateEvents_andNoLoginOnAnotherDevice() {

        String email = newEmail();
        String id = createUser(serviceAccount(), email);
        int before = events(id).size();

        KeycloakBrowser browser = new KeycloakBrowser(keycloak);
        browser.submit(browser.openForgotPassword(), Map.of("username", email));

        Mail reset = awaitMail(email, "Reset password");

        KeycloakBrowser otherBrowser = new KeycloakBrowser(keycloak);
        Page passwordForm = otherBrowser.openMailedLink(mailpit.firstLink(reset));
        otherBrowser.submit(passwordForm, Map.of("password-new", NEW_PASSWORD, "password-confirm", NEW_PASSWORD));

        // RESET_PASSWORD, a type the realm stores, is never fired by this flow: the reset ends in UPDATE_PASSWORD.
        assertEvents(id, before, "SEND_RESET_PASSWORD", "UPDATE_PASSWORD", "UPDATE_CREDENTIAL");

    }

    @Test
    void passwordChange_throughTheUpdatePasswordActionFiresTheUpdateEventsThenLogin() {

        String email = newEmail();
        String id = createUser(serviceAccount(), email);
        int before = events(id).size();

        KeycloakBrowser browser = new KeycloakBrowser(keycloak);
        Page passwordForm = browser.login("UPDATE_PASSWORD", email, PASSWORD);
        browser.submit(passwordForm, Map.of("password-new", NEW_PASSWORD, "password-confirm", NEW_PASSWORD));

        assertEvents(id, before, "UPDATE_PASSWORD", "UPDATE_CREDENTIAL", "LOGIN");

    }

    @Test
    void adminResetsAPassword_firesOnlyAnAdminEvent() {

        String token = serviceAccount();
        String id = createUser(token, newEmail());
        int before = events(id).size();

        Response response = keycloak.put(keycloak.adminUrl() + "/users/" + id + "/reset-password", token,
                Map.of("type", "password", "value", NEW_PASSWORD, "temporary", false));
        assertThat(response.status()).isEqualTo(204);

        // No UPDATE_PASSWORD, so no "Update password" mail either: a password set through the admin API is silent.
        assertEvents(id, before, "ADMIN_ACTION_USER");

    }

    // ---------------------------------------------------------------- login, tokens, logout

    @Test
    void login_inTheBrowserFiresLogin_andAWrongPasswordFiresLoginError() {

        String email = newEmail();
        String id = createUser(serviceAccount(), email);
        int before = events(id).size();

        KeycloakBrowser browser = new KeycloakBrowser(keycloak);
        browser.login(null, email, "Wrong-password-1");
        browser.login(null, email, PASSWORD);

        assertEvents(id, before, "LOGIN_ERROR", "LOGIN");

    }

    @Test
    void tokens_passwordGrantRefreshAndLogoutEachFireOneEvent() {

        String email = newEmail();
        String id = createUser(serviceAccount(), email);
        int before = events(id).size();

        Response grant = keycloak.passwordGrant(email, PASSWORD);
        assertThat(grant.status()).isEqualTo(200);
        String refreshToken = grant.json().path("refresh_token").asString();

        Response refreshed = keycloak.form(keycloak.realmUrl() + "/protocol/openid-connect/token", Map.of(
                "grant_type", "refresh_token", "client_id", "energy-cli", "client_secret", "secret", "refresh_token", refreshToken));
        assertThat(refreshed.status()).isEqualTo(200);

        Response logout = keycloak.form(keycloak.realmUrl() + "/protocol/openid-connect/logout", Map.of(
                "client_id", "energy-cli", "client_secret", "secret", "refresh_token", refreshed.json().path("refresh_token").asString()));
        assertThat(logout.status()).isEqualTo(204);

        assertEvents(id, before, "LOGIN", "REFRESH_TOKEN", "LOGOUT");

    }

    @Test
    void serviceAccount_clientCredentialsFireClientLogin() {

        String serviceAccountId = keycloak.findUserByUsername(master(), "service-account-user-service-admin").path("id").asString();
        int before = events(serviceAccountId).size();

        serviceAccount();

        assertEvents(serviceAccountId, before, "CLIENT_LOGIN");

    }

    // ---------------------------------------------------------------- what the backend does through the admin API

    @Test
    void emailChangeThroughTheAdminApi_firesOnlyAnAdminEvent_andSendsNoMail() {

        String token = serviceAccount();
        String oldEmail = newEmail();
        String newEmail = newEmail();
        String id = createUser(token, oldEmail);
        int before = events(id).size();
        int mailsBefore = mailpit.mailsTo(oldEmail).size() + mailpit.mailsTo(newEmail).size();

        Response response = keycloak.put(keycloak.adminUrl() + "/users/" + id, token, Map.of("email", newEmail));
        assertThat(response.status()).isEqualTo(204);

        // Keycloak itself says nothing to either address: the notices of the email-change feature are the backend's.
        assertEvents(id, before, "ADMIN_UPDATE_USER");
        assertThat(mailpit.mailsTo(oldEmail).size() + mailpit.mailsTo(newEmail).size()).isEqualTo(mailsBefore);

    }

    @Test
    void createAndDeleteThroughTheAdminApi_fireOnlyAdminEvents() {

        String token = serviceAccount();
        String id = createUser(token, newEmail());

        assertThat(keycloak.delete(keycloak.adminUrl() + "/users/" + id, token).status()).isEqualTo(204);

        // Creating with a password is one admin event (the password is part of the create).
        assertEvents(id, 0, "ADMIN_CREATE_USER", "ADMIN_DELETE_USER");

    }

    // ---------------------------------------------------------------- which mails come from the listener

    // "Verify email" and "Reset password" are sent by the flows themselves; only "Update password" is the email
    // listener's reaction to an event. Taking the listener out of the realm shows the difference.
    @Test
    void mails_verifyAndResetComeFromTheFlows_updatePasswordFromTheListener() {

        JsonNode original = keycloak.get(keycloak.adminUrl() + "/events/config", master()).json();

        Map<String, Object> withoutEmailListener = new LinkedHashMap<>();
        original.properties().forEach(property -> withoutEmailListener.put(property.getKey(), property.getValue()));
        withoutEmailListener.put("eventsListeners", List.of("jboss-logging"));

        assertThat(keycloak.put(keycloak.adminUrl() + "/events/config", master(), withoutEmailListener).status()).isEqualTo(204);

        try {

            String email = newEmail();
            KeycloakBrowser browser = new KeycloakBrowser(keycloak);
            browser.submit(browser.openRegistration(), Map.of("email", email));

            Page passwordForm = browser.openMailedLink(mailpit.firstLink(awaitMail(email, "Verify email")));
            browser.submit(passwordForm, Map.of("password-new", NEW_PASSWORD, "password-confirm", NEW_PASSWORD));

            String id = keycloak.findUser(master(), email).path("id").asString();
            assertEvents(id, 0, "REGISTER", "SEND_VERIFY_EMAIL", "VERIFY_EMAIL", "UPDATE_PASSWORD", "UPDATE_CREDENTIAL", "LOGIN");

            // The password was set (the events say so) and nothing else was mailed.
            assertThat(subjects(email)).containsExactly("Verify email");

            String resetEmail = newEmail();
            createUser(serviceAccount(), resetEmail);
            KeycloakBrowser resetBrowser = new KeycloakBrowser(keycloak);
            resetBrowser.submit(resetBrowser.openForgotPassword(), Map.of("username", resetEmail));

            awaitMail(resetEmail, "Reset password");

        } finally {

            assertThat(keycloak.put(keycloak.adminUrl() + "/events/config", master(), original).status()).isEqualTo(204);

        }

        assertThat(keycloak.get(keycloak.adminUrl() + "/events/config", master()).json().path("eventsListeners"))
                .isEqualTo(original.path("eventsListeners"));

    }

    // ---------------------------------------------------------------- helpers

    // Waits for the events of the user after the first `skip` ones and compares them, in order.
    private void assertEvents(String userId, int skip, String... expected) {

        await().atMost(Duration.ofSeconds(15)).untilAsserted(() -> {

            List<String> all = events(userId);

            assertThat(all.subList(Math.min(skip, all.size()), all.size())).containsExactly(expected);

        });

    }

    // Creates the user and waits until Keycloak has logged the creation, so that "the events so far" is a stable count.
    private String createUser(String token, String email) {

        String id = keycloak.createUser(token, email, PASSWORD, true);

        await().atMost(Duration.ofSeconds(15)).until(() -> events(id).contains("ADMIN_CREATE_USER"));

        return id;

    }

    // The events concerning one user, in the order Keycloak logged them.
    private List<String> events(String userId) {

        List<String> events = new ArrayList<>();

        for (String line : KEYCLOAK.getLogs().split("\\R")) {

            if (!EVENT_LINE.matcher(line).find()) {
                continue;
            }

            Matcher path = RESOURCE_PATH.matcher(line);
            Matcher operation = OPERATION.matcher(line);
            Matcher resource = RESOURCE_TYPE.matcher(line);

            if (operation.find() && resource.find() && path.find()) {

                // An admin event names its target in the resource path ("users/<id>", "users/<id>/reset-password").
                if (path.group(1).startsWith("users/" + userId)) {
                    events.add("ADMIN_" + operation.group(1) + "_" + resource.group(1));
                }

                continue;

            }

            Matcher type = TYPE.matcher(line);
            Matcher user = USER_ID.matcher(line);

            if (type.find() && user.find() && user.group(1).equals(userId)) {
                events.add(type.group(1));
            }

        }

        return events;

    }

    private Mail awaitMail(String address, String subject) {

        await().atMost(Duration.ofSeconds(15)).until(() -> subjects(address).contains(subject));

        return mailpit.mailsTo(address).stream().filter(m -> m.subject().equals(subject)).findFirst().orElseThrow();

    }

    private List<String> subjects(String address) {

        return mailpit.mailsTo(address).stream().map(Mail::subject).toList();

    }

    private String master() {

        return keycloak.masterAdminToken(MASTER_ADMIN_PASSWORD);

    }

    private String serviceAccount() {

        return keycloak.serviceAccountToken(ADMIN_CLIENT_ID, ADMIN_CLIENT_SECRET);

    }

    private static String newEmail() {

        return "ev-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";

    }
}
