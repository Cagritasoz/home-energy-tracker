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

        @NotBlank(message = "must not be blank")
        @Size(max = 100, message = "must be at most 100 characters")
        String firstName,

        @NotBlank(message = "must not be blank")
        @Size(max = 100, message = "must be at most 100 characters")
        String lastName,

        @NotBlank(message = "must not be blank")
        @Email(message = "must be a well-formed email address")
        @Size(max = 255, message = "must be at most 255 characters")
        String email,

        String address,

        // Wrapper objects to allow for null since omitting these two fields make sense.
        Boolean alertsEnabled,
        Double energyAlertingThreshold
) {
}
