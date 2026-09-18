package com.cagritasoz.user_service.config;

import lombok.NonNull;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import java.util.List;

@Component
public class AudienceValidator implements OAuth2TokenValidator<Jwt> {

    @Value("${app.security.required-audience}")
    private String requiredAudience;

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
