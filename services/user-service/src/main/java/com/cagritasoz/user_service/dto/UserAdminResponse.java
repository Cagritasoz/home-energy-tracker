package com.cagritasoz.user_service.dto;

import com.cagritasoz.user_service.model.UserStatus;
import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

// Admin view: every column of the users table, including the deletion-saga bookkeeping (status,
// deletionRequestedAt, deletionScheduledFor, deletionStartedAt, keycloakDeletedAt, deletedAt) and the services the
// deletion waits for (participants) an admin needs to see why a deletion is stuck. Same explicit ordering
// rationale as UserResponse.
@Builder
@JsonPropertyOrder({"id", "email", "displayName", "timezone", "status", "version", "deletionRequestedAt",
        "deletionScheduledFor", "deletionStartedAt", "keycloakDeletedAt", "deletedAt", "participants", "createdAt",
        "updatedAt"})
public record UserAdminResponse(

        UUID id,

        String email,

        String displayName,

        String timezone,

        UserStatus status,

        Long version,

        Instant deletionRequestedAt,

        Instant deletionScheduledFor,

        Instant deletionStartedAt,

        Instant keycloakDeletedAt,

        Instant deletedAt,

        // Empty until the promoter has started the deletion; then one entry per service it waits for.
        List<ParticipantResponse> participants,

        Instant createdAt,

        Instant updatedAt

) {
}
