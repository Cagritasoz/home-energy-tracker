package com.cagritasoz.user_service.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// Layer 1, and unlike every service test so far, no mocks at all - UpdateUserRequest has no
// dependencies to fake. A Validator reads the record's own @Size/@Pattern annotations and reports
// back which ones a given instance breaks, if any - the same mechanism Spring runs automatically
// behind @Valid on a controller parameter, just invoked by hand here instead of through a real
// HTTP request.
class UpdateUserRequestTest {

    // Building a ValidatorFactory does real classpath scanning and isn't free - one shared
    // instance for the whole class, not a new one per test.
    private static ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidatorFactory() {
        validatorFactory.close();
    }

    @Test
    void emptyBody_hasNoViolations() {
        UpdateUserRequest request = UpdateUserRequest.builder().build();

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void validDisplayNameAndTimezone_hasNoViolations() {
        UpdateUserRequest request = UpdateUserRequest.builder()
                .displayName("Arthur Morgan")
                .timezone("Europe/Istanbul")
                .build();

        assertThat(validator.validate(request)).isEmpty();
    }

    @Test
    void blankDisplayName_isRejected() {
        UpdateUserRequest request = UpdateUserRequest.builder().displayName("   ").build();

        Set<ConstraintViolation<UpdateUserRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        assertThat(violations.iterator().next().getMessage()).isEqualTo("must not be blank");
    }

    @Test
    void tooLongDisplayName_isRejected() {
        UpdateUserRequest request = UpdateUserRequest.builder().displayName("x".repeat(101)).build();

        assertThat(validator.validate(request))
                .extracting(ConstraintViolation::getMessage)
                .containsExactly("must be at most 100 characters");
    }

    // The compact constructor strips displayName BEFORE Bean Validation ever sees it - so a name
    // that's only blank once its surrounding whitespace is gone still has to be caught. This
    // proves the strip and the @Pattern check run in the right order relative to each other, not
    // just that each one works in isolation.
    @Test
    void displayNameBlankAfterStripping_isRejected() {
        UpdateUserRequest request = UpdateUserRequest.builder().displayName("   \t  ").build();

        assertThat(validator.validate(request)).hasSize(1);
    }

    @Test
    void displayNameWithSurroundingWhitespace_isStrippedBeforeStorage() {
        UpdateUserRequest request = UpdateUserRequest.builder().displayName("  Arthur Morgan  ").build();

        assertThat(request.displayName()).isEqualTo("Arthur Morgan");
    }

    @Test
    void timezone_currentlyAcceptsAnyValue() {
        // Documents, rather than enforces, today's real (if incomplete) behavior: timezone has no
        // validation annotation yet - see UpdateUserRequest's own comment on the field. This test
        // exists so the day someone adds @ValidTimezone, they have to come update or delete a
        // test that says the opposite, instead of the change slipping in silently.
        UpdateUserRequest request = UpdateUserRequest.builder().timezone("not-a-real-timezone").build();

        assertThat(validator.validate(request)).isEmpty();
    }
}
