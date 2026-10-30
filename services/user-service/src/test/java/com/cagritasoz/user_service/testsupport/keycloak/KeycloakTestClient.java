package com.cagritasoz.user_service.testsupport.keycloak;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

// REST access to a running Keycloak with the energy-tracker realm: tokens, the Account API and the admin API.
public final class KeycloakTestClient {

    public record Response(int status, String body) {

        public JsonNode json() {
            return MAPPER.readTree(body);
        }

    }

    public static final String REALM = "energy-tracker";

    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).build();

    private final String baseUrl;

    public KeycloakTestClient(String baseUrl) {
        this.baseUrl = baseUrl;
    }

    public String baseUrl() {
        return baseUrl;
    }

    public String realmUrl() {
        return baseUrl + "/realms/" + REALM;
    }

    public String adminUrl() {
        return baseUrl + "/admin/realms/" + REALM;
    }

    // A token of the master realm's bootstrap admin: what an infra operator holds (full rights).
    public String masterAdminToken(String password) {

        return tokenOf(form(baseUrl + "/realms/master/protocol/openid-connect/token", Map.of(
                "grant_type", "password", "client_id", "admin-cli", "username", "admin", "password", password)));

    }

    // The token user-service will use for the admin API.
    public String serviceAccountToken(String clientId, String secret) {

        return tokenOf(form(realmUrl() + "/protocol/openid-connect/token", Map.of(
                "grant_type", "client_credentials", "client_id", clientId, "client_secret", secret)));

    }

    // The development client's password grant: what scripts/get-token.ps1 does.
    public Response passwordGrant(String username, String password) {

        return form(realmUrl() + "/protocol/openid-connect/token", Map.of(
                "grant_type", "password", "client_id", "energy-cli", "client_secret", "secret",
                "username", username, "password", password));

    }

    public String userToken(String username, String password) {

        return tokenOf(passwordGrant(username, password));

    }

    public static JsonNode claimsOf(String jwt) {

        String payload = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);

        return MAPPER.readTree(payload);

    }

    public Response get(String url, String bearer) {
        return send("GET", url, bearer, null);
    }

    public Response put(String url, String bearer, Object jsonBody) {
        return send("PUT", url, bearer, jsonBody);
    }

    public Response post(String url, String bearer, Object jsonBody) {
        return send("POST", url, bearer, jsonBody);
    }

    public Response delete(String url, String bearer) {
        return send("DELETE", url, bearer, null);
    }

    // The user the admin API finds for an address (exact match), or null.
    public JsonNode findUser(String bearer, String email) {

        JsonNode users = get(adminUrl() + "/users?exact=true&email=" + encode(email), bearer).json();

        return users.isEmpty() ? null : users.get(0);

    }

    public JsonNode findUserByUsername(String bearer, String username) {

        JsonNode users = get(adminUrl() + "/users?exact=true&username=" + encode(username), bearer).json();

        return users.isEmpty() ? null : users.get(0);

    }

    // Creates an enabled user with a password; the realm's policy needs one with 10+ characters, a digit and both cases.
    public String createUser(String bearer, String email, String password, boolean enabled) {

        Map<String, Object> user = new LinkedHashMap<>();
        user.put("username", email);
        user.put("email", email);
        user.put("enabled", enabled);
        user.put("emailVerified", true);
        user.put("credentials", List.of(Map.of("type", "password", "value", password, "temporary", false)));

        Response response = post(adminUrl() + "/users", bearer, user);

        if (response.status() != 201) {
            throw new IllegalStateException("Creating " + email + " answered " + response.status() + ": " + response.body());
        }

        return findUser(bearer, email).path("id").asString();

    }

    public Response form(String url, Map<String, String> fields) {

        return execute(HttpRequest.newBuilder(URI.create(url))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(formBody(fields))));

    }

    // Construct the request body
    public static String formBody(Map<String, String> fields) {

        return fields.entrySet().stream()
                .map(e -> encode(e.getKey()) + "=" + encode(e.getValue()))
                .collect(Collectors.joining("&"));

    }

    // Translate to application/x-www-form-urlencoded
    public static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    private Response send(String method, String url, String bearer, Object jsonBody) {

        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                .header("Accept", "application/json")
                .method(method, jsonBody == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(MAPPER.writeValueAsString(jsonBody)));

        if (jsonBody != null) {
            builder.header("Content-Type", "application/json");
        }

        if (bearer != null) {
            builder.header("Authorization", "Bearer " + bearer);
        }

        return execute(builder);

    }

    private Response execute(HttpRequest.Builder builder) {

        try {

            HttpResponse<String> response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());

            return new Response(response.statusCode(), response.body());

        } catch (IOException e) {
            throw new IllegalStateException(e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }

    }

    // Get access token (jwt) as string.
    private static String tokenOf(Response response) {

        if (response.status() != 200) {
            throw new IllegalStateException("Token request answered " + response.status() + ": " + response.body());
        }

        return response.json().path("access_token").asString();

    }
}
