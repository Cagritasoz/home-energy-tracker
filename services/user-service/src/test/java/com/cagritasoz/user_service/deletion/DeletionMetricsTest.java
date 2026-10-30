package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.user.UserRepository;
import com.cagritasoz.user_service.user.UserStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

// The gauge names and what each one reads. The mock is a field because Micrometer holds a gauge's object weakly: a
// repository that only the lambda keeps alive could be collected and the gauge would read NaN.
@ExtendWith(MockitoExtension.class)
class DeletionMetricsTest {

    @Mock
    private UserRepository userRepository;

    private SimpleMeterRegistry meters;

    @BeforeEach
    void bind() {

        meters = new SimpleMeterRegistry();
        new DeletionMetrics(userRepository).bindTo(meters);

    }

    private double gauge(String name) {
        return meters.get(name).gauge().value();
    }

    @Test
    void scheduledAccounts_readsTheCountOfScheduledAccountsLive() {

        when(userRepository.countByStatus(UserStatus.DELETION_SCHEDULED)).thenReturn(4L, 9L);

        assertThat(gauge("user.deletion.scheduled.accounts")).isEqualTo(4);
        assertThat(gauge("user.deletion.scheduled.accounts")).isEqualTo(9);

    }

    @Test
    void oldestDeletingAge_readsTheRepositoryAndIsInSeconds() {

        when(userRepository.oldestDeletingAgeSeconds()).thenReturn(125.5);

        assertThat(gauge("user.deletion.deleting.oldest.age.seconds")).isEqualTo(125.5);
        assertThat(meters.get("user.deletion.deleting.oldest.age.seconds").gauge().getId().getBaseUnit()).isEqualTo("seconds");

    }

    @Test
    void oldestPendingKeycloakAge_readsItsOwnQuery() {

        when(userRepository.oldestPendingKeycloakDeleteAgeSeconds()).thenReturn(42.0);

        assertThat(gauge("user.deletion.keycloak.oldest.pending.age.seconds")).isEqualTo(42);

    }
}
