package com.cagritasoz.user_service.testsupport.jwt;

import com.cagritasoz.user_service.testsupport.data.UserFixtures;
import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.JWSSigner;
import com.nimbusds.jose.crypto.MACSigner;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

import java.text.ParseException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class TestTokens {

    public static final String ISSUER = "http://localhost:8080/realms/energy-tracker";

    public static final String AUDIENCE = "user-service";

    private final Map<String, Object> claims = new LinkedHashMap<>();

    private String keyId = LocalJwks.SIGNING_KEY_ID;

    private RSAKey signingKey = LocalJwks.SIGNING_KEY;

    private TestTokens() {
    }

    public static TestTokens forSubject(UUID subject) {
        TestTokens token = new TestTokens();
        token.claims.put("iss", ISSUER);
        token.claims.put("aud", List.of(AUDIENCE));
        token.claims.put("sub", subject.toString());
        token.claims.put("iat", Instant.now().getEpochSecond());
        token.claims.put("exp", Instant.now().plusSeconds(300).getEpochSecond());
        token.claims.put("email", UserFixtures.ARTHUR_MORGAN_EMAIL);
        token.claims.put("email_verified", true);
        token.claims.put("name", UserFixtures.ARTHUR_MORGAN_NAME);
        return token.roles();
    }

    public static TestTokens user(UUID subject) {
        return forSubject(subject).roles("user");
    }

    public static TestTokens admin(UUID subject) {
        return forSubject(subject).roles("admin");
    }

    public TestTokens roles(String... roles) {
        claims.put("realm_access", Map.of("roles", List.of(roles)));
        return this;
    }

    public TestTokens claim(String name, Object value) {
        claims.put(name, value);
        return this;
    }

    public TestTokens without(String name) {
        claims.remove(name);
        return this;
    }

    public TestTokens issuer(String issuer) {
        return claim("iss", issuer);
    }

    public TestTokens audience(String... audiences) {
        return claim("aud", new ArrayList<>(List.of(audiences)));
    }

    public TestTokens expiresAt(Instant instant) {
        claim("exp", instant.getEpochSecond());
        if ((Long) claims.get("iat") >= instant.getEpochSecond()) {
            claim("iat", instant.minusSeconds(300).getEpochSecond());
        }
        return this;
    }

    public TestTokens notBefore(Instant instant) {
        return claim("nbf", instant.getEpochSecond());
    }

    public TestTokens kid(String keyId) {
        this.keyId = keyId;
        return this;
    }

    public TestTokens signedWith(RSAKey key) {
        this.signingKey = key;
        return this;
    }

    public String signed() {
        return sign(JWSAlgorithm.RS256, rsaSigner()); // Keycloak uses RS256.
    }

    public String signedWithHmacUsingPublicKey() {
        try {
            return sign(JWSAlgorithm.HS256, new MACSigner(signingKey.toRSAPublicKey().getEncoded()));
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    public String unsigned() {
        try {
            return new PlainJWT(claimsSet()).serialize();
        } catch (ParseException e) {
            throw new IllegalStateException(e);
        }
    }

    public String bearer() {
        return "Bearer " + signed();
    }

    private JWSSigner rsaSigner() {
        try {
            return new RSASSASigner(signingKey); // Uses RSAkey generated via "test-key-1" key id.
        } catch (JOSEException e) {
            throw new IllegalStateException(e);
        }
    }

    private String sign(JWSAlgorithm algorithm, JWSSigner signer) { // Mimic keycloak signing with RS256
        try {
            SignedJWT jwt = new SignedJWT(
                    new JWSHeader.Builder(algorithm).keyID(keyId).type(JOSEObjectType.JWT).build(),
                    claimsSet());
            jwt.sign(signer);
            return jwt.serialize();
        } catch (JOSEException | ParseException e) {
            throw new IllegalStateException(e);
        }
    }

    private JWTClaimsSet claimsSet() throws ParseException {
        return JWTClaimsSet.parse(claims);
    }
}
