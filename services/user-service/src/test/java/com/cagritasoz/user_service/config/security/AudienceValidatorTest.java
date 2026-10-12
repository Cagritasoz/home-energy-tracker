package com.cagritasoz.user_service.config.security;

import com.cagritasoz.user_service.testsupport.JwtFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class AudienceValidatorTest {

    private static final String REQUIRED = "user-service";

    private final AudienceValidator validator = new AudienceValidator(REQUIRED);

    private Jwt.Builder token() {
        return JwtFixtures.minimalToken(UUID.randomUUID());
    }

    @Test
    void validate_audienceIsExactlyTheRequiredOne_succeeds() {
        Jwt jwt = token().audience(List.of(REQUIRED)).build();

        assertThat(validator.validate(jwt).hasErrors()).isFalse();
    }

    @Test
    void validate_requiredAudienceAmongSeveral_succeeds() {
        Jwt jwt = token().audience(List.of("account", REQUIRED, "other")).build();

        assertThat(validator.validate(jwt).hasErrors()).isFalse();
    }

    @Test
    void validate_onlyOtherAudiences_failsWithInvalidToken() {
        Jwt jwt = token().audience(List.of("account", "device-service")).build();

        OAuth2TokenValidatorResult result = validator.validate(jwt);

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).singleElement()
                .extracting(OAuth2Error::getErrorCode).isEqualTo(OAuth2ErrorCodes.INVALID_TOKEN);
    }

    @Test
    void validate_noAudienceClaim_fails() {
        assertThat(validator.validate(token().build()).hasErrors()).isTrue();
    }

    @Test
    void validate_audienceMatchIsCaseSensitive() {
        Jwt jwt = token().audience(List.of("User-Service")).build();

        assertThat(validator.validate(jwt).hasErrors()).isTrue();
    }

    @Test
    void validate_failureDoesNotLeakTokenData() {
        Jwt jwt = token().audience(List.of("account")).build();

        OAuth2Error error = validator.validate(jwt).getErrors().iterator().next();

        assertThat(error.getDescription()).isEqualTo("The token is not intended for this resource server");
        assertThat(error.getDescription()).doesNotContain("account");
    }

    @Test
    void validate_nullToken_isRejectedWithANullPointerException() {
        assertThatNullPointerException().isThrownBy(() -> validator.validate((Jwt) null));
    }
}
