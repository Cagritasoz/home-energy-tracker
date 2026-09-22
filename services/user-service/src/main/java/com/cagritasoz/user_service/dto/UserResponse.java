package com.cagritasoz.user_service.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;

import java.time.Instant;
import java.util.UUID;

// Jackson 3 orders record properties alphabetically by default and matches JSON keys to field
// names verbatim (no snake_case conversion) - hence the explicit @JsonPropertyOrder below.
// Self-service view (/users/me): only what the account owner needs. Lifecycle and saga fields
// (status, version, devicesDeleted, ...) live on UserAdminResponse - a caller who can reach this
// endpoint is always ACTIVE anyway, and keeping internals out of the public contract leaves the
// deletion saga free to change.
@Builder
@JsonPropertyOrder({"id", "email", "displayName", "timezone", "createdAt", "updatedAt"})
public record UserResponse(

        UUID id,

        String email,

        String displayName,

        String timezone,

        Instant createdAt,

        Instant updatedAt

) {
}
