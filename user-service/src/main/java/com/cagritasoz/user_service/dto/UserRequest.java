package com.cagritasoz.user_service.dto;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Builder;

// Used for both POST and PUT - same shape for create and full replace, like AlertRuleRequest.
// No id/createdAt/updatedAt here: those are server-assigned, never client input.
@Builder
public record UserRequest(

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

        String address
) {
}
