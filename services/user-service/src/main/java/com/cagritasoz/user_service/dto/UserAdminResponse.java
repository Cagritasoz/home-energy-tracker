package com.cagritasoz.user_service.dto;

import com.cagritasoz.user_service.model.UserStatus;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

// Admin view: every column of the users table, including the deletion-saga bookkeeping (status,
// devicesDeleted, keycloakDisabledAt, deletionRequestedAt, deletedAt) an admin needs to see why a
// deletion is stuck. Same explicit ordering rationale as UserResponse.
@Builder
@JsonPropertyOrder({"id", "email", "displayName", "timezone", "status", "version", "devicesDeleted",
        "keycloakDisabledAt", "deletionRequestedAt", "deletedAt", "createdAt", "updatedAt"})
public record UserAdminResponse(

        UUID id,

        String email,

        String displayName,

        String timezone,

        UserStatus status,

        Long version,

        boolean devicesDeleted,

        Instant keycloakDisabledAt,

        Instant deletionRequestedAt,

        Instant deletedAt,

        Instant createdAt,

        Instant updatedAt

) {
}
