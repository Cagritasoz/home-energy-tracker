package com.cagritasoz.user_service.security;

import com.cagritasoz.user_service.testsupport.jwt.LocalJwks;
import com.cagritasoz.user_service.testsupport.jwt.TestTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityAuthenticationTest extends SecuritySliceTest {

    private static final String PLAIN_CHALLENGE = "Bearer realm=\"energy-tracker\"";

    private static final String INVALID_TOKEN_CHALLENGE = "Bearer realm=\"energy-tracker\", error=\"invalid_token\"";

    static Stream<Route> everyRoute() {
        return Route.ALL.stream();
    }

    private TestTokens user() {
        return TestTokens.user(USER_ID);
    }

    private void assertUnauthorized(ResultActions result, String challenge) throws Exception {
        result.andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, challenge))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.type").value("https://home-energy-tracker/problems/unauthorized"))
                .andExpect(jsonPath("$.title").value("Unauthorized"))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
        verifyNoServiceWasCalled();
    }

    private void assertInvalidToken(String token) throws Exception {
        assertUnauthorized(call(Route.ME_GET, "Bearer " + token), INVALID_TOKEN_CHALLENGE);
    }

    @ParameterizedTest
    @MethodSource("everyRoute")
    void noAuthorizationHeader_everyRoute_returns401WithPlainChallenge(Route route) throws Exception {
        assertUnauthorized(call(route, null), PLAIN_CHALLENGE);
    }

    @ParameterizedTest
    @MethodSource("everyRoute")
    void invalidToken_everyRoute_returns401WithInvalidTokenChallenge(Route route) throws Exception {
        assertUnauthorized(call(route, "Bearer " + user().expiresAt(Instant.now().minusSeconds(3600)).signed()),
                INVALID_TOKEN_CHALLENGE);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Basic dXNlcjpwYXNz", "Digest abc", "Token abc", "abc"})
    void nonBearerScheme_isTreatedAsNoToken(String authorization) throws Exception {
        assertUnauthorized(call(Route.ME_GET, authorization), PLAIN_CHALLENGE);
    }

    @Test
    void tokenInQueryParameter_isNotAccepted() throws Exception {
        assertUnauthorized(mockMvc.perform(get("/api/v1/users/me").queryParam("access_token", user().signed())),
                PLAIN_CHALLENGE);
    }

    @Test
    void lowerCaseBearerScheme_isAccepted() throws Exception {
        call(Route.ME_GET, "bearer " + user().signed()).andExpect(status().isOk());
    }

    @Test
    void validToken_isAccepted() throws Exception {
        call(Route.ME_GET, user().bearer()).andExpect(status().isOk());
    }

    @ParameterizedTest
    @ValueSource(strings = {"garbage", "a.b", "a.b.c", "....", "eyJhbGciOiJSUzI1NiJ9.e30.c2ln"})
    void malformedToken_returns401WithInvalidTokenChallenge(String token) throws Exception {
        assertInvalidToken(token);
    }

    @ParameterizedTest
    @ValueSource(strings = {"Bearer", "Bearer ", "Bearer  ", "Bearer a b", "Bearer a,b"})
    void malformedAuthorizationHeader_returns401WithInvalidTokenChallenge(String authorization) throws Exception {
        assertUnauthorized(call(Route.ME_GET, authorization), INVALID_TOKEN_CHALLENGE);
    }

    @Test
    void expiredToken_isRejected() throws Exception {
        assertInvalidToken(user().expiresAt(Instant.now().minusSeconds(300)).signed());
    }

    @Test
    void tokenExpiredJustNow_isAcceptedWithinTheDefaultClockSkew() throws Exception {
        call(Route.ME_GET, user().expiresAt(Instant.now().minusSeconds(30)).bearer()).andExpect(status().isOk());
    }

    @Test
    void tokenExpiredLongerThanTheClockSkew_isRejected() throws Exception {
        assertInvalidToken(user().expiresAt(Instant.now().minusSeconds(120)).signed());
    }

    @Test
    void tokenNotYetValid_isRejected() throws Exception {
        assertInvalidToken(user().notBefore(Instant.now().plusSeconds(600)).signed());
    }

    @Test
    void tokenValidInAFewSeconds_isAcceptedWithinTheClockSkew() throws Exception {
        call(Route.ME_GET, user().notBefore(Instant.now().plusSeconds(30)).bearer()).andExpect(status().isOk());
    }

    // 1. An attacker has captured the kid of an existing key (it is readable in any token's header).
    // 2. The attacker generates their own RSA key pair (RSAKey) under that kid and signs a token
    //    with its private half.
    // 3. The attacker sends a request with the signed token.
    // 4. The decoder looks up that kid in its cached key set; on a cache miss it fetches the set from
    //    the JWKS endpoint (LocalJwks here), then verifies the signature with the matching public half.
    // 5. The attacker's private half is not the pair of the published public half -> invalid signature.
    // Even though both keys share the same kid, they are not a matching public/private pair.
    // Published keys may share a kid: every published key that matches the kid is tried in turn,
    // and only if all of them fail is the token invalid.
    @Test
    void tokenSignedWithAnotherKeyUnderTheSameKeyId_isRejected() throws Exception {
        assertInvalidToken(user().signedWith(LocalJwks.ATTACKER_KEY).signed());
    }

    // 1. The token is signed with the genuine private half of the published key (SIGNING_KEY, kid
    //    "test-key-1"), but its header names a different kid: "unknown-key".
    // 2. The decoder looks up "unknown-key" in its cached key set and finds nothing.
    // 3. Assuming the issuer may have rotated its keys, it fetches the key set again from the JWKS
    //    endpoint (LocalJwks here); the fresh set still holds only "test-key-1".
    // 4. No published key matches the kid -> no public half to verify with -> invalid token.
    // The signature would verify against SIGNING_KEY's public half, but the decoder never checks it:
    // it only tries the keys the header's kid points to, never "every key it knows".
    // A genuine signature is therefore not enough - the kid must also name the published key that
    // verifies it.
    @Test
    void tokenWithUnknownKeyId_isRejected() throws Exception {
        assertInvalidToken(user().kid("unknown-key").signed());
    }

    @Test
    void unsignedToken_isRejected() throws Exception {
        assertInvalidToken(user().unsigned());
    }

    // Algorithm confusion attack (RS256 -> HS256).
    // 1. RS256 is asymmetric: the private half signs, the public half verifies. HS256 is symmetric: one
    //    shared secret both signs and verifies.
    // 2. The public half is public by design, anyone can read it from the JWKS endpoint. The attacker
    //    uses its bytes as if they were an HS256 secret (MACSigner in signedWithHmacUsingPublicKey)
    //    and signs a token with it. The header says "alg": "HS256".
    // 3. A naive verifier trusts the alg in the header: it sees HS256, takes "the key" (the published
    //    public half) as the secret and recomputes the HMAC. It matches, because the attacker used
    //    exactly those bytes -> the forged token would be accepted without the private key.
    // 4. Here the decoder is built with a fixed expected algorithm (RS256, the default of
    //    NimbusJwtDecoder.withJwkSetUri) and only looks for keys usable with it. The token's header
    //    asks for HS256 -> no key qualifies -> the signature is never even checked -> invalid token.
    // The algorithm is decided by our configuration, never by the token that is being verified.
    @Test
    void tokenSignedWithHmacUsingThePublicKey_isRejected() throws Exception {
        assertInvalidToken(user().signedWithHmacUsingPublicKey());
    }

    @Test
    void tokenWithTamperedPayload_isRejected() throws Exception {
        String[] parts = user().signed().split("\\.");

        // Forge the claims.
        String forged = TestTokens.admin(ADMIN_ID).signedWith(LocalJwks.ATTACKER_KEY).signed().split("\\.")[1];

        assertInvalidToken(parts[0] + "." + forged + "." + parts[2]);
    }

    @Test
    void tokenFromAnotherIssuer_isRejected() throws Exception {
        assertInvalidToken(user().issuer("http://evil.example/realms/energy-tracker").signed());
    }

    @Test
    void tokenWithoutIssuer_isRejected() throws Exception {
        assertInvalidToken(user().without("iss").signed());
    }

    @Test
    void tokenForAnotherAudience_isRejected() throws Exception {
        assertInvalidToken(user().audience("account").signed());
    }

    @Test
    void tokenWithoutAudience_isRejected() throws Exception {
        assertInvalidToken(user().without("aud").signed());
    }

    @Test
    void tokenWithSeveralAudiencesIncludingOurs_isAccepted() throws Exception {
        call(Route.ME_GET, user().audience("account", "user-service").bearer()).andExpect(status().isOk());
    }

    @Test
    void tokenWithAudienceAsPlainString_isAccepted() throws Exception {
        call(Route.ME_GET, user().claim("aud", "user-service").bearer()).andExpect(status().isOk());
    }

    @Test
    void rejectedToken_isNeverEchoedInTheResponse() throws Exception {
        String token = user().expiresAt(Instant.now().minusSeconds(300)).signed();

        String body = call(Route.ME_GET, "Bearer " + token).andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(token);
    }
}
