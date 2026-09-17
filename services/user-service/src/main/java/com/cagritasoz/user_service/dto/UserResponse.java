package com.cagritasoz.user_service.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Builder;

import java.time.Instant;

// Jackson 3 orders record properties alphabetically by default and matches JSON keys to field
// names verbatim (no snake_case conversion) - hence the explicit @JsonPropertyOrder below.
@Builder
@JsonPropertyOrder({"id", "firstName", "lastName", "email", "address", "createdAt", "updatedAt"})
public record UserResponse(

        Long id,

        String firstName,

        String lastName,

        String email,

        String address,

        Instant createdAt,

        Instant updatedAt

) {
}
