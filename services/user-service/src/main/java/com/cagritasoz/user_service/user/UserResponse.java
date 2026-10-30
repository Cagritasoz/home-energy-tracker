package com.cagritasoz.user_service.user;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

// Jackson 3 orders record properties alphabetically by default and matches JSON keys to field
// names verbatim (no snake_case conversion) - hence the explicit @JsonPropertyOrder below.
// Self-service view (/users/me): what the account owner needs. status and deletionScheduledFor are here because
// the frontend decides from them whether to show the deletion banner and the Cancel button (the backend is the
// enforcement); a caller who can reach this endpoint is ACTIVE or DELETION_SCHEDULED, since the other states
// answer 410. The version and the other saga bookkeeping live on AdminUserResponse, which keeps internals out of
// the public contract and leaves the deletion saga free to change.
@Builder
@JsonPropertyOrder({"id", "email", "displayName", "timezone", "status", "deletionScheduledFor", "createdAt", "updatedAt"})
public record UserResponse(

        UUID id,

        String email,

        String displayName,

        String timezone,

        UserStatus status,

        // The moment the deletion becomes irreversible; null unless the account is DELETION_SCHEDULED.
        Instant deletionScheduledFor,

        Instant createdAt,

        Instant updatedAt

) {

    public static UserResponse from(User user) {

        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .timezone(user.getTimezone())
                .status(user.getStatus())
                .deletionScheduledFor(user.getDeletionScheduledFor())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();

    }
}
