package com.cagritasoz.user_service.testsupport;

import com.cagritasoz.user_service.entity.User;
import com.cagritasoz.user_service.model.UserStatus;

import java.time.Instant;
import java.util.UUID;

// Shared test fixtures for building User entities - a "test data builder": every method here
// returns an UNFINISHED User.UserBuilder (Lombok's generated builder; build() is deliberately
// never called in this class) with sensible, realistic defaults already set on every column.
// Each test then chains on only the specific fields it actually cares about before calling
// .build() itself, e.g. UserFixtures.activeUser().id(someId).email("a@b.com").build() - instead
// of every test class repeating its own private helper that re-lists all eleven columns.
public final class UserFixtures {

    private UserFixtures() {
    }

    // Every fixture defaults to Arthur Morgan unless a test needs a second or third, genuinely
    // distinct person (e.g. UserAdminServiceTest's multi-user listUsers test uses all three).
    // JwtFixtures.validUser() reuses these same constants for its own default email/name claims
    // rather than duplicating the literals there too - several tests rely on "the token's email
    // equals the stored email" being true by default, and two independently-typed string literals
    // that merely happen to match is exactly the kind of thing that quietly drifts apart later.
    public static final String ARTHUR_MORGAN_EMAIL = "arthur.morgan@example.com";
    public static final String ARTHUR_MORGAN_NAME = "Arthur Morgan";
    public static final String LEON_KENNEDY_EMAIL = "leon.kennedy@example.com";
    public static final String LEON_KENNEDY_NAME = "Leon Kennedy";
    public static final String JOHN_MARSTON_EMAIL = "john.marston@example.com";
    public static final String JOHN_MARSTON_NAME = "John Marston";
    public static final String JACK_CARVER_EMAIL = "jack.carver@example.com";
    public static final String JACK_CARVER_NAME = "Jack Carver";
    public static final String JASON_BRODY_EMAIL = "jason.brody@example.com";
    public static final String JASON_BRODY_NAME = "Jason Brody";

    public static User.UserBuilder minimalUser() {
        return User.builder()
                .id(UUID.randomUUID())
                .email(ARTHUR_MORGAN_EMAIL)
                .displayName(ARTHUR_MORGAN_NAME);
    }

    public static User.UserBuilder activeUser() {
        return User.builder()
                .id(UUID.randomUUID())
                .email(ARTHUR_MORGAN_EMAIL)
                .displayName(ARTHUR_MORGAN_NAME)
                .timezone("UTC")
                .status(UserStatus.ACTIVE)
                .version(0L)
                .devicesDeleted(false)
                .createdAt(Instant.parse("2026-01-01T00:00:00Z"))
                .updatedAt(Instant.parse("2026-01-01T00:00:00Z"));
    }

    // Builds on activeUser() rather than repeating every column - only what actually differs for
    // a DELETED account (the saga bookkeeping columns UserAdminResponse exposes) is overridden.
    public static User.UserBuilder deletedUser() {
        return activeUser()
                .status(UserStatus.DELETED)
                .version(12L)
                .devicesDeleted(true)
                .keycloakDisabledAt(Instant.parse("2026-02-01T00:00:00Z"))
                .deletionRequestedAt(Instant.parse("2026-01-31T23:58:42Z"))
                .deletedAt(Instant.parse("2026-02-01T00:00:05Z"))
                .updatedAt(Instant.parse("2026-02-01T00:00:05Z"));
    }
}
