package com.cagritasoz.user_service.relay;

import com.cagritasoz.user_service.repository.OutboxRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

// The gauges' names and what each one reads, with the repository and the relay mocked. The SQL behind the
// repository methods is OutboxRepositoryIT; the real wiring is RelaySchedulingIT.
//
// The mocks are fields on purpose: Micrometer keeps only a weak reference to the object a gauge reads
// from, so a mock that went out of scope could be garbage-collected and the gauge would turn into NaN.
// In the application Spring's singletons hold the real beans.
@ExtendWith(MockitoExtension.class)
class OutboxMetricsTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private OutboxRelay outboxRelay;

    private SimpleMeterRegistry registry;

    @BeforeEach
    void setUp() {

        registry = new SimpleMeterRegistry();
        new OutboxMetrics(outboxRepository, outboxRelay).bindTo(registry);

    }

    @Test
    void bindTo_registersTheFourGaugesUnderTheirDocumentedNames() {

        assertThat(registry.find("outbox.pending.rows").gauge()).isNotNull();
        assertThat(registry.find("outbox.oldest.pending.age.seconds").gauge()).isNotNull();
        assertThat(registry.find("outbox.parked.rows").gauge()).isNotNull();
        assertThat(registry.find("outbox.relay.consecutive.failed.ticks").gauge()).isNotNull();

    }

    @Test
    void ageGauge_declaresSecondsAsItsUnit() {

        Gauge age = registry.get("outbox.oldest.pending.age.seconds").gauge();

        assertThat(age.getId().getBaseUnit()).isEqualTo("seconds");

    }

    @Test
    void pendingGauge_readsTheRepositoryEachTimeNotOnceAtBindTime() {

        when(outboxRepository.countByPublishedAtIsNullAndParkedFalse()).thenReturn(5L, 9L);
        Gauge pending = registry.get("outbox.pending.rows").gauge();

        assertThat(pending.value()).isEqualTo(5.0);
        assertThat(pending.value()).isEqualTo(9.0);

    }

    @Test
    void ageGauge_readsTheRepository() {

        when(outboxRepository.oldestPendingAgeSeconds()).thenReturn(42.5);

        assertThat(registry.get("outbox.oldest.pending.age.seconds").gauge().value()).isEqualTo(42.5);

    }

    @Test
    void parkedGauge_readsTheRepository() {

        when(outboxRepository.countByParkedTrue()).thenReturn(3L);

        assertThat(registry.get("outbox.parked.rows").gauge().value()).isEqualTo(3.0);

    }

    @Test
    void failedTicksGauge_readsTheRelaysCurrentCount() {

        when(outboxRelay.consecutiveFailedTicks()).thenReturn(0, 4);
        Gauge failedTicks = registry.get("outbox.relay.consecutive.failed.ticks").gauge();

        assertThat(failedTicks.value()).isZero();
        assertThat(failedTicks.value()).isEqualTo(4.0);

    }

    @Test
    void ageGauge_databaseDown_readsNaNInsteadOfFailingTheScrape() {

        // "The age gauge reads NaN (Not a number) while the database is down." Micrometer catches the exception
        // from the value function, so one failing gauge cannot break the whole metrics endpoint and instead gets "Double.NaN" constant.
        when(outboxRepository.oldestPendingAgeSeconds()).thenThrow(new DataAccessResourceFailureException("connection lost"));

        assertThat(registry.get("outbox.oldest.pending.age.seconds").gauge().value()).isNaN();

    }
}
