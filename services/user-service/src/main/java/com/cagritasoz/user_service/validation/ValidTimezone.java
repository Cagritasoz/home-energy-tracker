package com.cagritasoz.user_service.validation;

import jakarta.validation.Constraint;
import jakarta.validation.Payload;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Bean Validation constraint: the annotated String must be a time zone id Java knows, i.e. one of
// ZoneId.getAvailableZoneIds() ("Europe/Istanbul", "UTC", "America/New_York", ...). The check itself is in
// TimezoneValidator. Like @Size and @Pattern, a null value passes - on a PATCH body null means "not sent,
// leave unchanged", so there is nothing to validate.
@Documented
@Constraint(validatedBy = TimezoneValidator.class)
@Target({ElementType.FIELD, ElementType.PARAMETER, ElementType.METHOD, ElementType.ANNOTATION_TYPE})
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidTimezone {

    String message() default "must be a valid time zone id, for example Europe/Istanbul";

    // The two attributes below are required by the Bean Validation specification for every constraint
    // annotation, even when unused: groups lets callers validate only part of a class, payload lets a caller
    // attach metadata (such as a severity) to the constraint.
    Class<?>[] groups() default {};

    Class<? extends Payload>[] payload() default {};

}
