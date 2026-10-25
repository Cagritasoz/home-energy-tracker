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
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

// The real realm file (infra/keycloak/realm-energy.json) imported into the real Keycloak image with the real
// mail catcher, and every property the application relies on checked against it: the email lock, the service
// account's rights, the mails Keycloak sends (and the ones it must not), registration and password reset.
//
// The images, and the one Keycloak setting that lives in the compose file instead of the realm (the email
// listener's include list), are read from infra/docker-compose.yml, so this test cannot drift from the stack
// a developer actually starts.
//
// One Keycloak for the whole class (a start takes about 20 seconds); every test creates its own users with
// unique addresses, so the tests do not depend on each other or on their order. The seeded accounts are only
// read, never changed.
@Testcontainers
class KeycloakRealmIT {

    private static final ComposeFile COMPOSE = ComposeFile.load();

    private static final String EMAIL_LISTENER_OPTION = "KC_SPI_EVENTS_LISTENER__EMAIL__INCLUDE_EVENTS";

    private static final String MASTER_ADMIN_PASSWORD = "test-master-admin-password";

    private static final String ADMIN_CLIENT_ID = "user-service-admin";

    private static final String ADMIN_CLIENT_SECRET = "test-user-service-admin-secret";

    private static final String PASSWORD = "Testpassword1";

    private static final String NEW_PASSWORD = "Newpassword123";

    private static final Network NETWORK = Network.newNetwork();

    @Container
    private static final GenericContainer<?> MAILPIT = new GenericContainer<>(COMPOSE.image("mailpit"))
            .withNetwork(NETWORK)
            .withNetworkAliases("mailpit") // The realm's SMTP host.
            .withExposedPorts(8025)
            .waitingFor(Wait.forHttp("/readyz").forPort(8025));

    @Container
    private static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(COMPOSE.image("keycloak"))
            .withNetwork(NETWORK)
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", MASTER_ADMIN_PASSWORD) // Override with our own password.
            .withEnv("KC_HTTP_ENABLED", "true")
            .withEnv("USER_SERVICE_ADMIN_CLIENT_SECRET", ADMIN_CLIENT_SECRET) // The ${...} placeholder in the realm file.
            .withEnv(EMAIL_LISTENER_OPTION, requireInCompose("keycloak", EMAIL_LISTENER_OPTION))
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

    // ---------------------------------------------------------------- settings

    @Test
    void realm_importsTheSettingsTheApplicationDependsOn() {

        JsonNode realm = keycloak.get(keycloak.adminUrl(), master()).json();

        assertThat(realm.path("verifyEmail").asBoolean()).isTrue();
        assertThat(realm.path("resetPasswordAllowed").asBoolean()).isTrue();
        assertThat(realm.path("registrationEmailAsUsername").asBoolean()).isTrue();
        assertThat(realm.path("duplicateEmailsAllowed").asBoolean()).isFalse();
        assertThat(realm.path("editUsernameAllowed").asBoolean()).isFalse();
        assertThat(realm.path("eventsEnabled").asBoolean()).isTrue();
        assertThat(realm.path("eventsExpiration").asLong()).isEqualTo(604800L);
        assertThat(realm.path("adminEventsEnabled").asBoolean()).isTrue();
        assertThat(realm.path("adminEventsDetailsEnabled").asBoolean()).isTrue();
        assertThat(strings(realm.path("eventsListeners"))).contains("jboss-logging", "email");
        assertThat(realm.path("smtpServer").path("host").asString()).isEqualTo("mailpit");
        assertThat(realm.path("smtpServer").path("port").asString()).isEqualTo("1025");

    }

    @Test
    void userProfile_everyAttributeIsEditableByAdminsOnly() {

        JsonNode profile = keycloak.get(keycloak.adminUrl() + "/users/profile", master()).json();

        List<String> names = new ArrayList<>();

        for (JsonNode attribute : profile.path("attributes")) {

            String name = attribute.path("name").asString();
            names.add(name);

            assertThat(strings(attribute.path("permissions").path("edit"))).as("edit permission of %s", name).containsExactly("admin");
            assertThat(strings(attribute.path("permissions").path("view"))).as("view permission of %s", name).contains("admin", "user");

        }

        assertThat(names).containsExactlyInAnyOrder("username", "email", "firstName", "lastName");

        // Names are not required: a user cannot enter them, and Keycloak would otherwise demand values it forbids.
        for (JsonNode attribute : profile.path("attributes")) {

            boolean required = attribute.has("required");

            assertThat(required).as("required: %s", attribute.path("name").asString())
                    .isEqualTo(attribute.path("name").asString().equals("email"));

        }

    }

    // ---------------------------------------------------------------- the service account

    @Test
    void serviceAccount_hasTheUserManagementRolesAndNothingMore() {

        String token = serviceAccount();

        List<String> roles = strings(KeycloakTestClient.claimsOf(token).path("resource_access").path("realm-management").path("roles"));

        assertThat(roles).contains("manage-users", "view-users");
        assertThat(roles).doesNotContain("realm-admin", "manage-realm", "manage-clients", "manage-events", "view-realm");

        // The realm settings come back stripped down (no mail server, no events configuration).
        JsonNode realm = keycloak.get(keycloak.adminUrl(), token).json();
        assertThat(realm.has("smtpServer")).isFalse();
        assertThat(realm.has("eventsListeners")).isFalse();

        assertThat(keycloak.get(keycloak.adminUrl() + "/clients", token).status()).isEqualTo(403);
        assertThat(keycloak.get(keycloak.adminUrl() + "/events", token).status()).isEqualTo(403);

    }

    @Test
    void serviceAccount_rejectsTheWrongSecret() {

        Response response = keycloak.form(keycloak.realmUrl() + "/protocol/openid-connect/token", Map.of(
                "grant_type", "client_credentials", "client_id", ADMIN_CLIENT_ID, "client_secret", "secret"));

        assertThat(response.status()).isEqualTo(401);

    }

    @Test
    void serviceAccount_canLookUpChangeAndDeleteAUser() {

        String token = serviceAccount();
        String email = newEmail();
        String other = newEmail();
        String id = keycloak.createUser(token, email, PASSWORD, true);
        keycloak.createUser(token, other, PASSWORD, true);

        // Locale.ROOT: with a Turkish default locale "i" would become a dotted capital I and the address would differ.
        assertThat(keycloak.findUser(token, email.toUpperCase(Locale.ROOT))).as("lookup ignores case").isNotNull();
        assertThat(keycloak.findUser(token, newEmail())).isNull();

        // A body with only the email also moves the username, and the address is stored lower-case.
        String moved = "Moved-" + newEmail();
        assertThat(keycloak.put(keycloak.adminUrl() + "/users/" + id, token, Map.of("email", moved)).status()).isEqualTo(204);

        JsonNode user = keycloak.get(keycloak.adminUrl() + "/users/" + id, token).json();
        assertThat(user.path("email").asString()).isEqualTo(moved.toLowerCase(Locale.ROOT));
        assertThat(user.path("username").asString()).isEqualTo(moved.toLowerCase(Locale.ROOT));

        // Changing to another user's address, in any case, is a conflict.
        Response conflict = keycloak.put(keycloak.adminUrl() + "/users/" + id, token, Map.of("email", other.toUpperCase(Locale.ROOT)));
        assertThat(conflict.status()).isEqualTo(409);
        assertThat(conflict.json().path("errorMessage").asString()).isEqualTo("User exists with same email");

        // Repeating a change that is already in place succeeds: the recovery job of a half-finished change relies on it.
        assertThat(keycloak.put(keycloak.adminUrl() + "/users/" + id, token, Map.of("email", moved)).status()).isEqualTo(204);

        assertThat(keycloak.delete(keycloak.adminUrl() + "/users/" + id, token).status()).isEqualTo(204);
        assertThat(keycloak.delete(keycloak.adminUrl() + "/users/" + id, token).status()).as("deleting twice").isEqualTo(404);

    }

    @Test
    void duplicateEmail_isRejectedEvenWhenTheExistingUserIsDisabled() {

        String token = serviceAccount();
        String email = newEmail();
        keycloak.createUser(token, email, PASSWORD, false);

        Response response = keycloak.post(keycloak.adminUrl() + "/users", token,
                Map.of("username", email, "email", email, "enabled", true));

        assertThat(response.status()).isEqualTo(409);

    }

    @Test
    void applicationAdmin_cannotUseKeycloaksAdminApi() {

        String token = keycloak.userToken("admin@example.com", "Adminpassword1");

        assertThat(keycloak.get(keycloak.adminUrl() + "/users", token).status()).isEqualTo(403);
        assertThat(keycloak.get(keycloak.adminUrl() + "/users/22222222-2222-2222-2222-222222222222", token).status()).isEqualTo(403);

    }

    @Test
    void adminEvents_recordWhoChangedAnEmail() {

        String token = serviceAccount();
        String id = keycloak.createUser(token, newEmail(), PASSWORD, true);
        String serviceAccountId = keycloak.findUserByUsername(master(), "service-account-user-service-admin").path("id").asString();

        keycloak.put(keycloak.adminUrl() + "/users/" + id, token, Map.of("email", newEmail()));

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {

            JsonNode events = keycloak.get(keycloak.adminUrl() + "/admin-events?resourceTypes=USER&operationTypes=UPDATE&max=50", master()).json();

            JsonNode mine = null;
            for (JsonNode event : events) {
                if (event.path("resourcePath").asString().equals("users/" + id)) {
                    mine = event;
                }
            }

            assertThat(mine).as("an admin event for the update").isNotNull();
            assertThat(mine.path("authDetails").path("userId").asString()).isEqualTo(serviceAccountId);
            assertThat(mine.has("representation")).as("the details are recorded").isTrue();

        });

    }

    // ---------------------------------------------------------------- the email lock

    @Test
    void accountApi_aChangeOfTheEmailChangesNothing() {

        String email = newEmail();
        String id = keycloak.createUser(serviceAccount(), email, PASSWORD, true);

        attemptEmailChangeThroughTheAccountApi(email, newEmail());

        assertThat(emailOf(id)).isEqualTo(email);

    }

    @Test
    void accountApi_aChangeOfTheNameIsRejected() {

        String email = newEmail();
        String id = keycloak.createUser(serviceAccount(), email, PASSWORD, true);
        String token = keycloak.userToken(email, PASSWORD);

        Map<String, Object> account = accountOf(token);
        account.put("firstName", "Changed");

        Response response = keycloak.post(keycloak.realmUrl() + "/account/", token, account);

        assertThat(response.status()).isEqualTo(400);
        assertThat(keycloak.get(keycloak.adminUrl() + "/users/" + id, master()).json().has("firstName")).isFalse();

    }

    // The weak lock is the realm flag (editUsernameAllowed = false makes the email read-only); the user profile is
    // the real one. Turning the flag on must not make the email editable.
    @Test
    void accountApi_theEmailStaysLockedWhenTheRealmFlagIsTurnedOn() {

        String email = newEmail();
        String id = keycloak.createUser(serviceAccount(), email, PASSWORD, true);

        assertThat(keycloak.put(keycloak.adminUrl(), master(), Map.of("editUsernameAllowed", true)).status()).isEqualTo(204);

        try {

            attemptEmailChangeThroughTheAccountApi(email, newEmail());

            assertThat(emailOf(id)).isEqualTo(email);

        } finally {

            assertThat(keycloak.put(keycloak.adminUrl(), master(), Map.of("editUsernameAllowed", false)).status()).isEqualTo(204);

        }

        assertThat(keycloak.get(keycloak.adminUrl(), master()).json().path("editUsernameAllowed").asBoolean()).isFalse();

    }

    @Test
    void updateProfileAction_showsDisabledFieldsAndRejectsAForcedSubmit() {

        String email = newEmail();
        String id = keycloak.createUser(serviceAccount(), email, PASSWORD, true);

        Page page = new KeycloakBrowser(keycloak).login("UPDATE_PROFILE", email, PASSWORD);

        assertThat(page.inputIsDisabled("firstName")).isTrue();
        assertThat(page.inputIsDisabled("lastName")).isTrue();
        assertThat(page.hasInput("email")).as("no email field").isFalse();

        KeycloakBrowser browser = new KeycloakBrowser(keycloak);
        Page forced = browser.submit(browser.login("UPDATE_PROFILE", email, PASSWORD),
                Map.of("firstName", "Hacker", "lastName", "Name", "email", newEmail()));

        assertThat(forced.text()).contains("read only");
        assertThat(emailOf(id)).isEqualTo(email);
        assertThat(keycloak.get(keycloak.adminUrl() + "/users/" + id, master()).json().has("firstName")).isFalse();

    }

    // Keycloak's own verify-before-change action would change the email inside Keycloak, where no account state can
    // be checked. It is off by default; if someone turns it on, this test must fail.
    @Test
    void updateEmailAction_isNotAvailable() {

        String email = newEmail();
        String id = keycloak.createUser(serviceAccount(), email, PASSWORD, true);

        Page page = new KeycloakBrowser(keycloak).login("UPDATE_EMAIL", email, PASSWORD);

        assertThat(page.leftForApplication()).as("no form, straight back to the application").isTrue();
        assertThat(emailOf(id)).isEqualTo(email);

    }

    // ---------------------------------------------------------------- registration, passwords and their mails

    @Test
    void registration_asksForTheEmailOnly_thenVerificationThenAPassword() {

        String email = newEmail();
        KeycloakBrowser browser = new KeycloakBrowser(keycloak);

        Page form = browser.openRegistration();
        assertThat(form.hasInput("email")).isTrue();
        assertThat(form.hasInput("firstName")).isFalse();
        assertThat(form.hasInput("lastName")).isFalse();
        assertThat(form.hasInput("password")).as("the password is chosen after the verification").isFalse();

        Page afterSubmit = browser.submit(form, Map.of("email", email));
        assertThat(afterSubmit.text()).contains("verify your email address");

        Mail verification = awaitMail(email, "Verify email");
        assertThat(verification.to()).containsExactly(email);

        Page passwordForm = browser.openMailedLink(mailpit.firstLink(verification));
        assertThat(passwordForm.hasInput("password-new")).isTrue();

        Page done = browser.submit(passwordForm, Map.of("password-new", NEW_PASSWORD, "password-confirm", NEW_PASSWORD));
        assertThat(done.leftForApplication()).isTrue();

        Response grant = keycloak.passwordGrant(email, NEW_PASSWORD);
        assertThat(grant.status()).isEqualTo(200);

        JsonNode claims = KeycloakTestClient.claimsOf(grant.json().path("access_token").asString());
        assertThat(claims.path("email").asString()).isEqualTo(email);
        assertThat(claims.path("email_verified").asBoolean()).isTrue();

        // The verification, plus the notice every password change gets (the first password counts as one).
        awaitMail(email, "Update password");
        assertThat(subjects(email)).containsExactlyInAnyOrder("Verify email", "Update password");

    }

    @Test
    void forgotPassword_theLinkIsOnTheLoginPage_andTheResetWorks() {

        String email = newEmail();
        keycloak.createUser(serviceAccount(), email, PASSWORD, true);

        KeycloakBrowser browser = new KeycloakBrowser(keycloak);
        Page form = browser.openForgotPassword();

        Page afterSubmit = browser.submit(form, Map.of("username", email));
        assertThat(afterSubmit.text()).contains("You should receive an email shortly");

        Mail reset = awaitMail(email, "Reset password");

        KeycloakBrowser otherBrowser = new KeycloakBrowser(keycloak); // The mail is opened on another device.
        Page passwordForm = otherBrowser.openMailedLink(mailpit.firstLink(reset));
        assertThat(passwordForm.hasInput("password-new")).isTrue();

        Page finished = otherBrowser.submit(passwordForm, Map.of("password-new", NEW_PASSWORD, "password-confirm", NEW_PASSWORD));

        // Opened on another device there is no login session to continue, so Keycloak ends on its own "Account
        // updated" page. The client's Home URL gives that page a way back to the application.
        assertThat(finished.text()).contains("Account updated");
        assertThat(finished.linkContaining("http://localhost:3000")).as("Back to Application").isNotNull();

        assertThat(keycloak.passwordGrant(email, NEW_PASSWORD).status()).isEqualTo(200);
        assertThat(keycloak.passwordGrant(email, PASSWORD).status()).as("the old password").isEqualTo(400);

        awaitMail(email, "Update password");
        assertThat(subjects(email)).containsExactlyInAnyOrder("Reset password", "Update password");

    }

    // The mailed verification link of a registration opened on another device: there is no login session there, so
    // the user is not signed in and Keycloak ends on its own page with a way back to the application.
    @Test
    void registration_whenTheLinkIsOpenedOnAnotherDevice_endsOnAccountUpdatedWithoutASession() {

        String email = newEmail();
        KeycloakBrowser browser = new KeycloakBrowser(keycloak);

        browser.submit(browser.openRegistration(), Map.of("email", email));

        Mail verification = awaitMail(email, "Verify email");

        KeycloakBrowser otherDevice = new KeycloakBrowser(keycloak);
        Page passwordForm = otherDevice.openMailedLink(mailpit.firstLink(verification));
        Page finished = otherDevice.submit(passwordForm, Map.of("password-new", NEW_PASSWORD, "password-confirm", NEW_PASSWORD));

        assertThat(finished.leftForApplication()).as("no automatic redirect").isFalse();
        assertThat(finished.text()).contains("Account updated");
        assertThat(finished.linkContaining("http://localhost:3000")).as("Back to Application").isNotNull();
        assertThat(otherDevice.isSignedIn()).as("no session was created").isFalse();

        assertThat(keycloak.passwordGrant(email, NEW_PASSWORD).status()).as("the account is usable").isEqualTo(200);

    }

    // What the frontend's "change password" button does for a user who is already signed in: Keycloak shows the
    // password page straight away (no second login), the page has a Cancel button because the application started
    // the action, and both buttons send the browser back to the application.
    @Test
    void passwordChangeAction_forASignedInUser_skipsTheLogin_andReturnsToTheApplicationOnSubmitAndOnCancel() {

        String email = newEmail();
        keycloak.createUser(serviceAccount(), email, PASSWORD, true);

        KeycloakBrowser browser = new KeycloakBrowser(keycloak);
        assertThat(browser.login(null, email, PASSWORD).leftForApplication()).isTrue();
        assertThat(browser.isSignedIn()).isTrue();

        Page passwordForm = browser.openLogin("UPDATE_PASSWORD");
        assertThat(passwordForm.hasInput("username")).as("no second login").isFalse();
        assertThat(passwordForm.hasInput("password-new")).isTrue();
        assertThat(passwordForm.text()).contains("Cancel");

        Page done = browser.submit(passwordForm, Map.of("password-new", NEW_PASSWORD, "password-confirm", NEW_PASSWORD));
        assertThat(done.leftForApplication()).isTrue();
        assertThat(done.url()).contains("kc_action_status=success");

        Page again = browser.openLogin("UPDATE_PASSWORD");
        Page cancelled = browser.submit(again, Map.of("cancel-aia", "true"));
        assertThat(cancelled.leftForApplication()).isTrue();
        assertThat(cancelled.url()).contains("kc_action_status=cancelled");

        assertThat(keycloak.passwordGrant(email, NEW_PASSWORD).status()).as("the cancelled run changed nothing").isEqualTo(200);

    }

    // Keycloak's own Account Console is a separate public client of the realm with its own authorization-code flow
    // with PKCE: PKCE protects a code exchange, it does not keep a user away from a client. So today any user of the
    // realm who types /realms/energy-tracker/account can sign in to it. This test pins that exposure; when the
    // account clients are disabled (see the README), it must change to expect the "Client disabled" error.
    @Test
    void accountConsole_anyUserCanSignInToItAtTheMoment() {

        String email = newEmail();
        keycloak.createUser(serviceAccount(), email, PASSWORD, true);

        String console = keycloak.realmUrl() + "/account/";

        KeycloakBrowser browser = new KeycloakBrowser(keycloak);
        Page login = browser.openLoginAs("account-console", console);

        Page afterLogin = browser.signIn(login, email, PASSWORD);

        assertThat(afterLogin.url()).startsWith(console).contains("code=");

    }

    // The email listener ignores the realm's event-type list: without the include list set on the server it mails the
    // owner on every failed login (a way to mail-bomb someone) and sends two mails per password change.
    @Test
    void passwordChange_sendsOneNotice_andAFailedLoginSendsNothing() {

        String email = newEmail();
        keycloak.createUser(serviceAccount(), email, PASSWORD, true);

        assertThat(keycloak.passwordGrant(email, "Wrong-password-1").status()).isEqualTo(400);
        assertThat(keycloak.passwordGrant(email, PASSWORD).status()).isEqualTo(200);

        KeycloakBrowser browser = new KeycloakBrowser(keycloak);
        Page passwordForm = browser.login("UPDATE_PASSWORD", email, PASSWORD);
        Page done = browser.submit(passwordForm, Map.of("password-new", NEW_PASSWORD, "password-confirm", NEW_PASSWORD));
        assertThat(done.leftForApplication()).isTrue();

        awaitMail(email, "Update password");

        // The failed login came first: a "Login error" mail would already be here.
        assertThat(subjects(email)).containsExactly("Update password");

    }

    // ---------------------------------------------------------------- helpers

    private void attemptEmailChangeThroughTheAccountApi(String currentEmail, String newEmail) {

        String token = keycloak.userToken(currentEmail, PASSWORD);

        // Like the account console: read the account, change the email, post it back whole.
        Map<String, Object> account = accountOf(token);
        account.put("email", newEmail);
        account.put("username", newEmail);

        Response response = keycloak.post(keycloak.realmUrl() + "/account/", token, account);

        // Silently ignored (2xx) or rejected (4xx): what matters is the outcome, which the caller asserts.
        assertThat(response.status()).isNotEqualTo(500);

    }

    private String emailOf(String id) {

        return keycloak.get(keycloak.adminUrl() + "/users/" + id, master()).json().path("email").asString();

    }

    private Mail awaitMail(String address, String subject) {

        await().atMost(Duration.ofSeconds(15)).until(() -> subjects(address).contains(subject));

        return mailpit.mailsTo(address).stream().filter(m -> m.subject().equals(subject)).findFirst().orElseThrow();

    }

    private List<String> subjects(String address) {

        return mailpit.mailsTo(address).stream().map(Mail::subject).toList();

    }

    // Master tokens live only 60 seconds, so every use asks for a fresh one.
    private String master() {

        return keycloak.masterAdminToken(MASTER_ADMIN_PASSWORD);

    }

    private String serviceAccount() {

        return keycloak.serviceAccountToken(ADMIN_CLIENT_ID, ADMIN_CLIENT_SECRET);

    }

    private static String newEmail() {

        return "it-" + UUID.randomUUID().toString().substring(0, 8) + "@example.com";

    }

    private static List<String> strings(JsonNode array) {

        List<String> values = new ArrayList<>();

        for (JsonNode element : array) {
            values.add(element.asString());
        }

        return values;

    }

    private static String requireInCompose(String service, String variable) {

        String value = COMPOSE.environment(service, variable);

        if (value == null) {
            throw new IllegalStateException("docker-compose.yml no longer sets " + variable + " on " + service
                    + ": without it Keycloak mails the owner on every failed login");
        }

        return value;

    }

    // The editable fields of the account as the Account API returns them, ready to be changed and posted back whole.
    private Map<String, Object> accountOf(String token) {

        JsonNode account = keycloak.get(keycloak.realmUrl() + "/account/", token).json();

        Map<String, Object> fields = new LinkedHashMap<>();

        for (String name : List.of("username", "firstName", "lastName", "email")) {
            if (account.has(name)) {
                fields.put(name, account.path(name).asString());
            }
        }

        return fields;

    }
}
