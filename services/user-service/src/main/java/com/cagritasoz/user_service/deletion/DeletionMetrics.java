package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.user.UserRepository;
import com.cagritasoz.user_service.user.UserStatus;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import org.jspecify.annotations.NonNull;
import org.springframework.stereotype.Component;

// The saga's watchdog gauges, like OutboxMetrics: read live from the database on every scrape. They say where
// deletions are, so a stuck one shows up as a number that only grows. (The pending confirmations per participant are
// ParticipantAckMetrics, next to the participants they are about; the Keycloak client's circuit state is
// keycloak.admin.circuit.state.)
//
// Alert rules for these arrive with the Prometheus endpoint: an age above what the participants and the quiet period
// explain means a stuck deletion.
@Component
public class DeletionMetrics implements MeterBinder {

    private final UserRepository userRepository;

    public DeletionMetrics(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    @Override
    public void bindTo(@NonNull MeterRegistry registry) {

        Gauge.builder("user.deletion.scheduled.accounts", userRepository,
                        repository -> repository.countByStatus(UserStatus.DELETION_SCHEDULED))
                .description("Accounts in the grace period")
                .register(registry);

        Gauge.builder("user.deletion.deleting.oldest.age.seconds", userRepository, UserRepository::oldestDeletingAgeSeconds)
                .description("Age of the account that has been DELETING the longest, 0 when none")
                .baseUnit("seconds")
                .register(registry);

        Gauge.builder("user.deletion.keycloak.oldest.pending.age.seconds", userRepository, UserRepository::oldestPendingKeycloakDeleteAgeSeconds)
                .description("How long the longest-waiting DELETING account has been without its Keycloak user deleted, 0 when none")
                .baseUnit("seconds")
                .register(registry);

    }
}
