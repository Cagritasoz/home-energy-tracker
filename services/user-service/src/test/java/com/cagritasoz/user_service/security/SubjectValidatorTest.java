package com.cagritasoz.user_service.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Locale;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class SubjectValidatorTest {

    private static final String ID = "3f2504e0-4f89-11d3-9a0c-0305e82c3301";

    private final SubjectValidator validator = new SubjectValidator();

    private Jwt tokenWithSubject(String subject) {
        return Jwt.withTokenValue("test-token").header("alg", "none").subject(subject).build();
    }

    private Jwt tokenWithoutSubject() {
        return Jwt.withTokenValue("test-token").header("alg", "none").claim("email", "someone@example.com").build();
    }

    @Test
    void validate_lowerCaseUuid_succeeds() {
        assertThat(validator.validate(tokenWithSubject(ID)).hasErrors()).isFalse();
    }

    @Test
    void validate_upperCaseUuid_succeeds() {
        assertThat(validator.validate(tokenWithSubject(ID.toUpperCase(Locale.ROOT))).hasErrors()).isFalse();
    }

    @Test
    void validate_randomUuid_succeeds() {
        assertThat(validator.validate(tokenWithSubject(UUID.randomUUID().toString())).hasErrors()).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "not-a-uuid",
            "f:3f2504e0-4f89-11d3-9a0c-0305e82c3301:alice",     // the shape of a federated user's id
            "1-1-1-1-1",                                         // UUID.fromString accepts it
            "3f2504e04f8911d39a0c0305e82c3301",                  // no hyphens
            " 3f2504e0-4f89-11d3-9a0c-0305e82c3301",             // surrounding whitespace
            "3f2504e0-4f89-11d3-9a0c-0305e82c3301 ",
            "3f2504e0-4f89-11d3-9a0c-0305e82c3301\n",
            "3f2504e0-4f89-11d3-9a0c-0305e82c33011",             // one digit too many
            "3f2504e0-4f89-11d3-9a0c-0305e82c330",               // one digit too few
            "3f2504e0-4f89-11d3-9a0c-0305e82c330g",              // not hexadecimal
            "{3f2504e0-4f89-11d3-9a0c-0305e82c3301}",
            "urn:uuid:3f2504e0-4f89-11d3-9a0c-0305e82c3301",
            "",
            "   "})
    void validate_subjectThatIsNotACanonicalUuid_failsWithInvalidToken(String subject) {
        OAuth2TokenValidatorResult result = validator.validate(tokenWithSubject(subject));

        assertThat(result.hasErrors()).isTrue();
        assertThat(result.getErrors()).singleElement()
                .extracting(OAuth2Error::getErrorCode).isEqualTo(OAuth2ErrorCodes.INVALID_TOKEN);
    }

    @Test
    void validate_noSubjectClaim_fails() {
        assertThat(validator.validate(tokenWithoutSubject()).hasErrors()).isTrue();
    }

    @Test
    void validate_whatItAcceptsAlwaysParsesAsAUuid() {
        String subject = UUID.randomUUID().toString().toUpperCase(Locale.ROOT);

        assertThat(validator.validate(tokenWithSubject(subject)).hasErrors()).isFalse();
        assertThat(UUID.fromString(subject)).isNotNull();
    }

    @Test
    void validate_failureDoesNotLeakTokenData() {
        OAuth2Error error = validator.validate(tokenWithSubject("not-a-uuid")).getErrors().iterator().next();

        assertThat(error.getDescription()).isEqualTo("The token does not identify a user of this service");
        assertThat(error.getDescription()).doesNotContain("not-a-uuid");
    }

    @Test
    void validate_nullToken_isRejectedWithANullPointerException() {
        assertThatNullPointerException().isThrownBy(() -> validator.validate((Jwt) null));
    }
}
