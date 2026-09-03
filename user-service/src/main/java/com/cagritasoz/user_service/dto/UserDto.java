package com.cagritasoz.user_service.dto;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

// Jackson matches JSON keys to fields by exact name (no snake_case conversion),
// so "first_name" won't bind to firstName unless a naming strategy/@JsonProperty is added.
@Data
@Builder
@NoArgsConstructor // Jackson needs a no args constructor.
@AllArgsConstructor
// Jackson 3 (Spring Boot 4) defaults to alphabetical property ordering instead of
// declaration order, so @JsonPropertyOrder is needed to keep the response field order stable.
@JsonPropertyOrder({"id", "firstName", "lastName", "email", "address", "alertsEnabled", "energyAlertingThreshold"})
public class UserDto {
    private Long id;

    @NotBlank
    @Size(max = 100)
    private String firstName;

    @NotBlank
    @Size(max = 100)
    private String lastName;

    @NotBlank
    @Email
    @Size(max = 255)
    private String email;

    private String address;

    private boolean alertsEnabled;
    private double energyAlertingThreshold;
}


