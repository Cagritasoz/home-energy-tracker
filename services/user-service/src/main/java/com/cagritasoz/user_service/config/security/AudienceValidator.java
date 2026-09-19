package com.cagritasoz.user_service.config.security;

import lombok.NonNull;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;

public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    private final String requiredAudience;

    public AudienceValidator(String requiredAudience) {
        this.requiredAudience = requiredAudience;
    }

    @Override
    @NonNull
    public OAuth2TokenValidatorResult validate(@NonNull Jwt jwt) {

        List<String> audiences = jwt.getAudience();

        if(audiences != null && audiences.contains(requiredAudience)) {

            return OAuth2TokenValidatorResult.success();

        }

        return OAuth2TokenValidatorResult.failure(new OAuth2Error
                (OAuth2ErrorCodes.INVALID_TOKEN,
                        "The token is not intended for this resource server",
                        null));

    }
}
