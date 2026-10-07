package com.cagritasoz.user_service.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.time.ZoneId;
import java.util.Set;

// The check behind @ValidTimezone: the value must be exactly one of the ids in ZoneId.getAvailableZoneIds().
//
// That set holds region ids ("Europe/Istanbul", "UTC", "Etc/GMT-3", ...). It deliberately does not hold
// offsets such as "+03:00" or "UTC+3" (ZoneId.of() would accept those, but an offset does not follow daylight
// saving time and is not what the user means by "my time zone"), and the comparison is case-sensitive, so
// "europe/istanbul" is rejected: the stored value is always the canonical spelling and the same zone is never
// stored two ways. No trimming either - " UTC" is rejected rather than silently repaired.
//
// A null value is valid: on a PATCH, null means "not sent". (An empty string is not null, and fails.)
public class TimezoneValidator implements ConstraintValidator<ValidTimezone, String> {

    // getAvailableZoneIds() builds a fresh copy of ~600 ids on every call, so take it once. The set only
    // changes if the JVM's time zone data is replaced at runtime, which this service never does.
    private static final Set<String> AVAILABLE_ZONE_IDS = Set.copyOf(ZoneId.getAvailableZoneIds());

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {

        return value == null || AVAILABLE_ZONE_IDS.contains(value);

    }
}
