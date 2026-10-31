package com.cagritasoz.user_service.deletion;

import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import org.hibernate.validator.constraints.time.DurationMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;
import java.util.HashSet;
import java.util.List;

// app.user.deletion.* (explained in application.yaml). The jobs' intervalMs and ackConsumer.enabled are also read by
// annotations, which take only a placeholder; they are fields here so that the key is validated too.
@Validated
@ConfigurationProperties(prefix = "app.user.deletion", ignoreUnknownFields = false)
public record DeletionProperties(
        @NotNull @DurationMin(nanos = 1, message = "must be positive") Duration grace,
        @NotNull @DurationMin(nanos = 0, message = "must not be negative") Duration quietPeriod,
        // The same rule as the table's chk_user_deletion_participants_name: a name it rejects would fail every promotion.
        @NotNull List<@Pattern(regexp = "^[a-z][a-z0-9_-]{0,62}$", message = "must match ^[a-z][a-z0-9_-]{0,62}$") String> requiredParticipants,
        @NotNull @Valid Job promoter,
        @NotNull @Valid Job keycloak,
        @NotNull @Valid Job finalizer,
        @NotNull @Valid AckConsumer ackConsumer) {

    public record Job(@Positive long intervalMs, @Positive int batchSize) {
    }

    public record AckConsumer(boolean enabled) {
    }

    @AssertTrue(message = "must not list a participant twice")
    public boolean isRequiredParticipantsUnique() {

        return requiredParticipants == null || new HashSet<>(requiredParticipants).size() == requiredParticipants.size();

    }
}
