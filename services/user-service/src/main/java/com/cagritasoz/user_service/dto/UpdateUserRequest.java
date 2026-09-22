package com.cagritasoz.user_service.dto;

import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import lombok.Builder;

// PATCH body for /users/me: a partial update, so every field is optional. Absent and an explicit
// null both mean "leave unchanged" - a plain record can't tell them apart, and it doesn't matter
// here because neither column is nullable, so there is nothing a null could legitimately clear.
// An empty body {} is therefore a valid no-op.
// Only fields the account owner may change live here: email is owned by Keycloak, and
// id/status/timestamps are server-managed.
@Builder
public record UpdateUserRequest(

        // Bean Validation skips null values for @Size and @Pattern, so these only fire when the
        // client actually sent a name. @NotBlank can't be used instead of the @Pattern: it also
        // rejects null, which here just means "not sent". The pattern requires at least one
        // non-whitespace character; (?s) lets \S look across line breaks too.
        @Size(max = 100, message = "must be at most 100 characters")
        @Pattern(regexp = "(?s).*\\S.*", message = "must not be blank")
        String displayName,

        // JIT provisioning creates every account as UTC; the client sets the real one here.
        // Not validated as an IANA zone id yet - anything is accepted and stored as sent.
        String timezone

) {

    // Runs during construction, i.e. before Bean Validation sees the object, so the length and
    // blank checks above apply to the trimmed name and the service can use it as it is.
    public UpdateUserRequest {

        if (displayName != null) {

            displayName = displayName.strip();

        }
    }
}
