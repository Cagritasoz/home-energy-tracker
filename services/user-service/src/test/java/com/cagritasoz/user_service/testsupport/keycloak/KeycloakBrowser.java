package com.cagritasoz.user_service.testsupport.keycloak;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

// Plays a browser against Keycloak's own pages (login, registration, required actions, mail links): follows
// redirects, keeps cookies, reads the form of the page it is on and posts it. Pages are plain HTML, so a few
// regular expressions are enough here.
//
// Cookies and redirects are handled by hand on purpose: Keycloak marks its cookies "Secure" even over
// http://localhost (a real browser accepts that on localhost, java.net.CookieManager does not send them), and a
// redirect to the application's redirect_uri must stop the flow instead of being followed to a server that does not exist.
public final class KeycloakBrowser {

    public record Page(int status, String url, String body, boolean leftForApplication) {

        // The visible text, for assertions on what the user would read.
        public String text() {

            String withoutHead = body
                    .replaceAll("(?s)<script.*?</script>|<style.*?</style>|<head>.*?</head>", "");

            return unescape(withoutHead.replaceAll("<[^>]+>", " ")).replaceAll("\\s+", " ").strip();

        }

        public String formAction() {

            Matcher matcher = Pattern.compile("<form[^>]*action=\"([^\"]+)\"").matcher(body);

            return matcher.find() ? unescape(matcher.group(1)) : null;

        }

        public boolean hasInput(String name) {
            return inputTag(name) != null;
        }

        public boolean inputIsDisabled(String name) {

            String tag = inputTag(name);

            return tag != null && tag.contains("disabled");

        }

        // The first link of the page (the "Click here to proceed" of a mailed action link).
        public String firstLink() {

            Matcher matcher = Pattern.compile("<a [^>]*href=\"([^\"]+)\"").matcher(body);

            return matcher.find() ? unescape(matcher.group(1)) : null;

        }

        public String linkContaining(String fragment) {

            Matcher matcher = Pattern.compile("href=\"([^\"]*" + Pattern.quote(fragment) + "[^\"]*)\"").matcher(body);

            return matcher.find() ? unescape(matcher.group(1)) : null;

        }

        private String inputTag(String name) {

            Matcher matcher = Pattern.compile("<input[^>]*name=\"" + Pattern.quote(name) + "\"[^>]*>").matcher(body);

            return matcher.find() ? matcher.group() : null;

        }

        private static String unescape(String html) {

            return html.replace("&amp;", "&").replace("&quot;", "\"").replace("&lt;", "<")
                    .replace("&gt;", ">").replace("&#39;", "'").replace("&#x2F;", "/");

        }
    }

    private static final String APPLICATION_REDIRECT = "http://localhost:3000/cb";

    private final HttpClient http = HttpClient.newBuilder()
            .version(HttpClient.Version.HTTP_1_1)
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final Map<String, String> cookies = new LinkedHashMap<>();

    private final KeycloakTestClient keycloak;

    // Inject the keycloak test client.
    public KeycloakBrowser(KeycloakTestClient keycloak) {
        this.keycloak = keycloak;
    }

    // The login page; with a kc_action ("UPDATE_PASSWORD", "UPDATE_PROFILE", ...) Keycloak runs that action
    // right after the user signs in.
    public Page openLogin(String kcAction) {

        return get(keycloak.realmUrl() + "/protocol/openid-connect/auth?" + authenticationParameters(kcAction));

    }

    public Page signIn(Page loginPage, String username, String password) {

        return post(loginPage.formAction(), Map.of("username", username, "password", password));

    }

    public Page login(String kcAction, String username, String password) {

        return signIn(openLogin(kcAction), username, password);

    }

    // The authorization endpoint for another client of the realm, as that client's own JavaScript would open it
    // (the account console is a public client with its own redirect address).
    public Page openLoginAs(String clientId, String redirectUri) {

        return get(keycloak.realmUrl() + "/protocol/openid-connect/auth?" + authenticationParameters(clientId, redirectUri, null));

    }

    // Whether Keycloak holds a sign-in session for this browser (its identity cookie), which is what makes a later
    // visit skip the login page.
    public boolean isSignedIn() {
        return cookies.containsKey("KEYCLOAK_IDENTITY");
    }

    public Page openRegistration() {

        return get(keycloak.realmUrl() + "/protocol/openid-connect/registrations?" + authenticationParameters(null));

    }

    // The "Forgot Password?" page, reached through the link on the login page like a user would.
    public Page openForgotPassword() {

        Page login = openLogin(null);

        String link = login.linkContaining("reset-credentials");

        if (link == null) {
            throw new IllegalStateException("The login page has no forgot-password link");
        }

        return get(absolute(link));

    }

    // Posts the fields to the form of the page the browser is on.
    public Page submit(Page page, Map<String, String> fields) {

        return post(page.formAction(), fields);

    }

    // Opens a link from a mail. Keycloak first shows "Click here to proceed"; the page after that is the next
    // step (a form), which can already be the result when the same browser holds the session.
    public Page openMailedLink(String link) {

        Page page = get(link);

        if (page.formAction() == null && page.firstLink() != null) {
            return get(absolute(page.firstLink()));
        }

        return page;

    }

    private String authenticationParameters(String kcAction) {

        return authenticationParameters("energy-cli", APPLICATION_REDIRECT, kcAction);

    }

    private String authenticationParameters(String clientId, String redirectUri, String kcAction) {

        byte[] verifier = new byte[32];
        new SecureRandom().nextBytes(verifier);

        String challenge = Base64.getUrlEncoder().withoutPadding()
                .encodeToString(sha256(Base64.getUrlEncoder().withoutPadding().encodeToString(verifier)));

        Map<String, String> parameters = new LinkedHashMap<>();
        parameters.put("client_id", clientId);
        parameters.put("response_type", "code");
        parameters.put("scope", "openid");
        parameters.put("redirect_uri", redirectUri);
        parameters.put("code_challenge", challenge);
        parameters.put("code_challenge_method", "S256");

        if (kcAction != null) {
            parameters.put("kc_action", kcAction);
        }

        return KeycloakTestClient.formBody(parameters);

    }

    private Page get(String url) {
        return send(url, null);
    }

    private Page post(String url, Map<String, String> fields) {
        return send(url, KeycloakTestClient.formBody(fields));
    }

    private Page send(String startUrl, String formBody) {

        String url = startUrl;
        String body = formBody;

        for (int hop = 0; hop < 12; hop++) {

            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url));

            if (!cookies.isEmpty()) {
                builder.header("Cookie", cookies.entrySet().stream()
                        .map(e -> e.getKey() + "=" + e.getValue())
                        .reduce((a, b) -> a + "; " + b).orElse(""));
            }

            if (body == null) {
                builder.GET();
            } else {
                builder.header("Content-Type", "application/x-www-form-urlencoded")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
            }

            HttpResponse<String> response;

            try {
                response = http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }

            remember(response);

            int status = response.statusCode();

            if (status >= 300 && status < 400) {

                String location = URI.create(url).resolve(response.headers().firstValue("Location").orElseThrow()).toString();

                if (location.startsWith(APPLICATION_REDIRECT)) {
                    return new Page(status, location, "", true);
                }

                url = location;
                body = null; // A redirect after a POST is followed with a GET.
                continue;

            }

            return new Page(status, url, response.body(), false);

        }

        throw new IllegalStateException("Too many redirects from " + startUrl);

    }

    private void remember(HttpResponse<String> response) {

        for (String header : response.headers().allValues("Set-Cookie")) {

            String pair = header.split(";", 2)[0];
            int equals = pair.indexOf('=');
            String name = pair.substring(0, equals);
            String value = pair.substring(equals + 1);

            if (value.isBlank() || value.equals("\"\"") || header.contains("Max-Age=0")) {
                cookies.remove(name);
            } else {
                cookies.put(name, value);
            }

        }

    }

    private String absolute(String link) {

        return link.startsWith("http") ? link : keycloak.baseUrl() + link;

    }

    private static byte[] sha256(String value) {

        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.US_ASCII));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }

    }
}
