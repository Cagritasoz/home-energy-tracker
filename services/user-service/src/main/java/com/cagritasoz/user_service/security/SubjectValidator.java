package com.cagritasoz.user_service.security;

import lombok.NonNull;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.regex.Pattern;

// The subject of a token is the key of the users table, so every place that reads it parses a UUID. Checking it here, in
// the validator chain, puts the rule at the trust boundary: a token with any other subject is answered 401 and never
// becomes an authentication, instead of failing with a 500 at the first place that parses it. Such a token can only be
// one that Keycloak itself signed, for example for a user taken from an LDAP federation.
public class SubjectValidator implements OAuth2TokenValidator<Jwt> {

    // The canonical 8-4-4-4-12 form, in either case. UUID.fromString on its own is not a check: it also accepts
    // shortened forms such as "1-1-1-1-1".
    private static final Pattern UUID_FORMAT =
            Pattern.compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");

    @Override
    @NonNull
    public OAuth2TokenValidatorResult validate(@NonNull Jwt jwt) {

        String subject = jwt.getSubject();

        if(subject != null && UUID_FORMAT.matcher(subject).matches()) {

            return OAuth2TokenValidatorResult.success();

        }

        // The value is not echoed: nothing about why a token failed is for the client.
        return OAuth2TokenValidatorResult.failure(new OAuth2Error
                (OAuth2ErrorCodes.INVALID_TOKEN,
                        "The token does not identify a user of this service",
                        null));

    }
}
