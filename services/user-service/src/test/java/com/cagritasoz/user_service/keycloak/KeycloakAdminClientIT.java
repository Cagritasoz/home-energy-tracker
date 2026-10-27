package com.cagritasoz.user_service.keycloak;

import com.cagritasoz.user_service.testsupport.containers.ComposeFile;
import com.cagritasoz.user_service.testsupport.keycloak.KeycloakTestClient;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.MountableFile;

import java.time.Clock;
import java.time.Duration;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// The real KeycloakAdminRestClient against the real Keycloak image with the project's real realm file, authenticating
// as the real service account (user-service-admin, with only manage-users and view-users). KeycloakAdminRestClientTest
// pins how the client reacts to every answer a Keycloak can give, using stubs; this class checks that the real Keycloak
// really gives those answers: the token request, the paths, 204 for a delete, 404 for a user that is not there, and
// that the service account's rights are enough for exactly what the client does.
//
// One Keycloak for the whole class (a start takes about 20 seconds); every test creates its own users. Users are made
// with the master admin, because the application's service account cannot create users and the client must not need to.
@Testcontainers
class KeycloakAdminClientIT {

    private static final ComposeFile COMPOSE = ComposeFile.load();

    private static final String MASTER_ADMIN_PASSWORD = "test-master-admin-password";

    private static final String CLIENT_ID = "user-service-admin";

    private static final String CLIENT_SECRET = "test-user-service-admin-secret";

    private static final String PASSWORD = "Testpassword1";

    // The seeded customer of the realm file (fixed id: a local-dev realm only). Read, never changed.
    private static final UUID SEEDED_TEST_USER = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Container
    private static final GenericContainer<?> KEYCLOAK = new GenericContainer<>(COMPOSE.image("keycloak"))
            .withCommand("start-dev", "--import-realm")
            .withEnv("KC_BOOTSTRAP_ADMIN_USERNAME", "admin")
            .withEnv("KC_BOOTSTRAP_ADMIN_PASSWORD", MASTER_ADMIN_PASSWORD)
            .withEnv("KC_HTTP_ENABLED", "true")
            .withEnv("USER_SERVICE_ADMIN_CLIENT_SECRET", CLIENT_SECRET) // The ${...} placeholder in the realm file.
            .withCopyFileToContainer(MountableFile.forHostPath(ComposeFile.repoFile("infra/keycloak/realm-energy.json")),
                    "/opt/keycloak/data/import/realm-energy.json")
            .withExposedPorts(8080)
            .waitingFor(Wait.forHttp("/realms/energy-tracker").forPort(8080).withStartupTimeout(Duration.ofMinutes(3)));

    private static KeycloakTestClient keycloak;

    private static String baseUrl;

    @BeforeAll
    static void connect() {

        baseUrl = "http://" + KEYCLOAK.getHost() + ":" + KEYCLOAK.getMappedPort(8080);
        keycloak = new KeycloakTestClient(baseUrl);

    }

    // Built the way the application builds it (the same factory methods and the settings of application.yaml).
    private static KeycloakAdminRestClient client(String secret) {

        return new KeycloakAdminRestClient(
                KeycloakAdminConfig.restClient(baseUrl, Duration.ofSeconds(1), Duration.ofSeconds(3)),
                KeycloakAdminConfig.circuitBreaker(10, 5, 50f, Duration.ofSeconds(30), 2),
                KeycloakAdminConfig.retry(2, Duration.ofMillis(200)),
                "energy-tracker", CLIENT_ID, secret, Duration.ofSeconds(30), Clock.systemUTC());

    }

    private static String master() {

        return keycloak.masterAdminToken(MASTER_ADMIN_PASSWORD);

    }

    private static String newEmail() {

        return "client-it." + UUID.randomUUID().toString().substring(0, 8) + "@example.com";

    }

    private static UUID createUser(String email) {

        return UUID.fromString(keycloak.createUser(master(), email, PASSWORD, true));

    }

    @Test
    void userExists_existingUser_isTrue() {

        UUID id = createUser(newEmail());

        assertThat(client(CLIENT_SECRET).userExists(id)).isTrue();

    }

    @Test
    void userExists_seededUser_isTrueAndTheUserIsLeftAlone() {

        assertThat(client(CLIENT_SECRET).userExists(SEEDED_TEST_USER)).isTrue();

        assertThat(keycloak.passwordGrant("testuser@example.com", PASSWORD).status()).as("still able to log in").isEqualTo(200);

    }

    @Test
    void userExists_unknownId_isFalse() {

        assertThat(client(CLIENT_SECRET).userExists(UUID.randomUUID())).isFalse();

    }

    // What the saga needs: afterwards the user does not exist, cannot log in, and the address can be registered again.
    @Test
    void deleteUser_existingUser_removesItFromKeycloak() {

        String email = newEmail();
        UUID id = createUser(email);
        KeycloakAdminRestClient client = client(CLIENT_SECRET);
        assertThat(keycloak.passwordGrant(email, PASSWORD).status()).as("able to log in before").isEqualTo(200);

        client.deleteUser(id);

        assertThat(client.userExists(id)).isFalse();
        KeycloakTestClient.Response login = keycloak.passwordGrant(email, PASSWORD);
        assertThat(login.status()).as("able to log in after").isEqualTo(400); // Keycloak answers invalid_grant with 400.
        assertThat(login.json().path("error").asString()).isEqualTo("invalid_grant");
        assertThat(keycloak.findUser(master(), email)).isNull();
        assertThat(createUser(email)).as("the address is free again").isNotEqualTo(id);

    }

    // Idempotent against the real thing: the second delete (a retry, or a second instance) and a delete of a user that
    // never existed both answer 404, which the client reports as success.
    @Test
    void deleteUser_alreadyDeletedOrNeverExisted_isSuccess() {

        UUID id = createUser(newEmail());
        KeycloakAdminRestClient client = client(CLIENT_SECRET);

        client.deleteUser(id);

        assertThatCode(() -> client.deleteUser(id)).doesNotThrowAnyException();
        assertThatCode(() -> client.deleteUser(UUID.randomUUID())).doesNotThrowAnyException();

    }

    // The same client instance keeps working across many calls with one cached token.
    @Test
    void severalCallsOnOneClient_allSucceed() {

        KeycloakAdminRestClient client = client(CLIENT_SECRET);

        for (int i = 0; i < 5; i++) {

            UUID id = createUser(newEmail());

            assertThat(client.userExists(id)).isTrue();
            client.deleteUser(id);
            assertThat(client.userExists(id)).isFalse();

        }

    }

    // A wrong secret is rejected by the real token endpoint, and the client reports it as a failure that retrying will
    // not fix, without the secret in the message.
    @Test
    void wrongClientSecret_failsAndIsNotRetryable() {

        assertThatThrownBy(() -> client("not-the-secret").deleteUser(UUID.randomUUID()))
                .isInstanceOfSatisfying(KeycloakUnavailableException.class, e -> assertThat(e.retryable()).isFalse())
                .hasMessageNotContaining("not-the-secret")
                .hasMessageContaining("401");

    }

    // A user of the realm other than the customers made above; only the lookup is made, nothing is changed.
    @Test
    void userExists_seededAdminAccount_isTrue() {

        UUID seededAdmin = UUID.fromString("22222222-2222-2222-2222-222222222222");

        assertThat(client(CLIENT_SECRET).userExists(seededAdmin)).isTrue();

    }
}
