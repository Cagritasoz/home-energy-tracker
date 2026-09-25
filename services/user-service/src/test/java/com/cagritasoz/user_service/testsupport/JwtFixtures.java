package com.cagritasoz.user_service.testsupport;

import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

// Shared test fixtures for building Jwt tokens - same "unfinished builder with defaults" shape as
// UserFixtures.
public final class JwtFixtures {

    private JwtFixtures() {
    }

    // A realistic, nothing-wrong-with-it token: a subject, a verified email, and a "name" claim
    // so the display-name fallback chain never has to run unless a test deliberately builds on
    // minimalToken() instead. header("alg", "none") plus at least one claim are the only two
    // things Jwt itself actually requires in order to build successfully.
    //
    // The email/name claims reuse UserFixtures.ARTHUR_MORGAN_EMAIL/_NAME rather than repeating the
    // literals here - see UserFixtures' own comment on those constants for why.
    public static Jwt.Builder validUser(UUID subject) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject.toString())
                .claim("email", UserFixtures.ARTHUR_MORGAN_EMAIL)
                .claim("email_verified", true)
                .claim("name", UserFixtures.ARTHUR_MORGAN_NAME);
    }

    // Only a subject - the starting point for "this claim is genuinely missing" tests. Build on
    // this rather than validUser(id).claim("someClaim", null): claim()'s value parameter isn't
    // nullable (spring-security-oauth2-jose's Jwt package is @NullMarked), so passing null there
    // works at runtime but violates the API's own declared contract, which IDEs correctly flag.
    // Starting from a token that never had the claim at all sidesteps that entirely, and reads
    // more honestly anyway - "this token doesn't have an email claim" rather than "this token has
    // an email claim explicitly set to null".
    public static Jwt.Builder minimalToken(UUID subject) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject.toString());
    }
}
