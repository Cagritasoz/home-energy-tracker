package com.cagritasoz.user_service.dto;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import jakarta.validation.ValidatorFactory;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.ZoneId;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

// Layer 1, and unlike every service test so far, no mocks at all - UpdateUserRequest has no
// dependencies to fake. A Validator reads the record's own @Size/@Pattern/@ValidTimezone annotations and reports
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

    // ---- timezone: must be one of ZoneId.getAvailableZoneIds() --------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {"Europe/Istanbul", "UTC", "America/New_York", "Asia/Tokyo", "Etc/GMT-3"})
    void validTimezone_hasNoViolations(String timezone) {
        UpdateUserRequest request = UpdateUserRequest.builder().timezone(timezone).build();

        assertThat(validator.validate(request)).isEmpty();
    }

    // The validator and ZoneId.getAvailableZoneIds() must agree on every single id, not just on the few
    // examples above - otherwise a legitimate zone could be turned away.
    @Test
    void everyIdJavaKnows_isAccepted() {
        for (String zoneId : ZoneId.getAvailableZoneIds()) {
            UpdateUserRequest request = UpdateUserRequest.builder().timezone(zoneId).build();

            assertThat(validator.validate(request)).as(zoneId).isEmpty();
        }
    }

    // "not-a-real-timezone": nonsense. "" and "   ": blank is not "not sent" (that is null), so it is
    // rejected. " UTC" / "UTC ": surrounding whitespace is not trimmed (displayName is, timezone is
    // deliberately exact). "europe/istanbul": the match is case-sensitive, so one zone is never stored in
    // two spellings. "+03:00", "UTC+3", "Z": ZoneId.of() would take these, but they are fixed offsets, not
    // the region ids the user means by "my time zone", and they are not in getAvailableZoneIds().
    @ParameterizedTest
    @ValueSource(strings = {"not-a-real-timezone", "", "   ", " UTC", "UTC ", "europe/istanbul", "Istanbul",
            "+03:00", "UTC+3", "Z"})
    void invalidTimezone_isRejectedWithTheTimezoneMessage(String timezone) {
        UpdateUserRequest request = UpdateUserRequest.builder().timezone(timezone).build();

        Set<ConstraintViolation<UpdateUserRequest>> violations = validator.validate(request);

        assertThat(violations).hasSize(1);
        ConstraintViolation<UpdateUserRequest> violation = violations.iterator().next();
        assertThat(violation.getPropertyPath().toString()).isEqualTo("timezone");
        assertThat(violation.getMessage()).isEqualTo("must be a valid time zone id, for example Europe/Istanbul");
    }

    // null means "not sent, leave unchanged" - the same rule as for displayName - so it is never validated.
    @Test
    void timezoneNotSent_isNotValidated() {
        UpdateUserRequest request = UpdateUserRequest.builder().displayName("Arthur Morgan").timezone(null).build();

        assertThat(validator.validate(request)).isEmpty();
    }

    // The two fields are validated independently: each bad one is reported, neither hides the other.
    @Test
    void invalidDisplayNameAndInvalidTimezone_areBothReported() {
        UpdateUserRequest request = UpdateUserRequest.builder().displayName("   ").timezone("nowhere").build();

        assertThat(validator.validate(request))
                .extracting(violation -> violation.getPropertyPath().toString())
                .containsExactlyInAnyOrder("displayName", "timezone");
    }
}
