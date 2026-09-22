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

    public static User.UserBuilder activeUser() {
        return User.builder()
                .id(UUID.randomUUID())
                .email("test.user@example.com")
                .displayName("Test User")
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
