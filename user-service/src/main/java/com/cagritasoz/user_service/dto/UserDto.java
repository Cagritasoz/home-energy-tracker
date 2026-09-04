package com.cagritasoz.user_service.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

// Jackson matches JSON keys to fields by exact name (no snake_case conversion),
// so "first_name" won't bind to firstName unless a naming strategy/@JsonProperty is added.
// Jackson 3 (Spring Boot 4) defaults to alphabetical property ordering instead of
// declaration order, so @JsonPropertyOrder is needed to keep the response field order stable.
@Builder
@JsonPropertyOrder({"id", "firstName", "lastName", "email", "address", "alertsEnabled", "energyAlertingThreshold"})
public record UserDto(
        Long id,

        @NotBlank
        @Size(max = 100)
        String firstName,

        @NotBlank
        @Size(max = 100)
        String lastName,

        @NotBlank
        @Email
        @Size(max = 255)
        String email,

        String address,

        // Wrapper objects to allow for null since omitting these two fields make sense.
        Boolean alertsEnabled,
        Double energyAlertingThreshold
) {
}
