package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.testsupport.config.ConfigAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;

// app.user.deletion.* as the application binds it, from the real application.yaml with one setting changed. A setting
// the saga cannot run with stops the start-up instead of reaching a job or a query as a value that makes it misbehave.
class DeletionPropertiesTest {

    @EnableConfigurationProperties(DeletionProperties.class)
    static class Enable {
    }

    private final ApplicationContextRunner runner = ConfigAssertions.runnerFor(Enable.class);

    @Test
    void applicationYaml_bindsEverySetting() {

        runner.run(context -> {

            DeletionProperties properties = context.getBean(DeletionProperties.class);

            assertThat(properties.grace()).isEqualTo(Duration.ofMinutes(5));
            assertThat(properties.quietPeriod()).isEqualTo(Duration.ofMinutes(1));
            assertThat(properties.requiredParticipants()).isEmpty();
            assertThat(properties.promoter()).isEqualTo(new DeletionProperties.Job(3000, 50));
            assertThat(properties.keycloak()).isEqualTo(new DeletionProperties.Job(3000, 20));
            assertThat(properties.finalizer().batchSize()).isEqualTo(50);

        });

    }

    // A key that matches nothing is almost always a typo, and without this it would be ignored and the default kept.
    @Test
    void unknownKey_stopsTheStartUp() {

        ConfigAssertions.assertUnknownKeyRefused(runner, "app.user.deletion.graze=1d");

    }

    // --- the grace period

    // Zero or negative would make an account deletable at once while the API still promises a cancel window.
    @ParameterizedTest
    @ValueSource(strings = {"0s", "-1s", "-5m"})
    void grace_zeroOrNegative_stopsTheStartUp(String grace) {

        ConfigAssertions.assertRefused(runner, "app.user.deletion.grace=" + grace, "must be positive");

    }

    @Test
    void grace_oneNanosecond_isAccepted() {

        ConfigAssertions.assertAccepted(runner, "app.user.deletion.grace=1ns");

    }

    // --- the quiet period

    // A negative period would finalize an account before its Keycloak user is deleted.
    @ParameterizedTest
    @ValueSource(strings = {"-1s", "-10m"})
    void quietPeriod_negative_stopsTheStartUp(String quietPeriod) {

        ConfigAssertions.assertRefused(runner, "app.user.deletion.quiet-period=" + quietPeriod, "must not be negative");

    }

    @Test
    void quietPeriod_zero_isAccepted() {

        ConfigAssertions.assertAccepted(runner, "app.user.deletion.quiet-period=0s");

    }

    // --- the jobs

    @ParameterizedTest
    @ValueSource(strings = {"promoter", "keycloak", "finalizer"})
    void job_batchSizeBelowOne_stopsTheStartUp(String job) {

        ConfigAssertions.assertRefused(runner, "app.user.deletion." + job + ".batch-size=0", "Positive");
        ConfigAssertions.assertRefused(runner, "app.user.deletion." + job + ".batch-size=-1", "Positive");

    }

    @ParameterizedTest
    @ValueSource(strings = {"promoter", "keycloak", "finalizer"})
    void job_intervalBelowOne_stopsTheStartUp(String job) {

        ConfigAssertions.assertRefused(runner, "app.user.deletion." + job + ".interval-ms=0", "Positive");

    }

    // --- the required participants

    @Test
    void requiredParticipants_validNames_areBoundAsAList() {

        runner.withPropertyValues("app.user.deletion.required-participants=devices,usage,alert_rules,a-1").run(context ->
                assertThat(context.getBean(DeletionProperties.class).requiredParticipants())
                        .containsExactly("devices", "usage", "alert_rules", "a-1"));

    }

    @Test
    void requiredParticipants_empty_meansNobodyIsWaitedFor() {

        runner.withPropertyValues("app.user.deletion.required-participants=").run(context ->
                assertThat(context.getBean(DeletionProperties.class).requiredParticipants()).isEmpty());

    }

    // Without this check the first promotion would fail on chk_user_deletion_participants_name, and so would every later
    // one, because the failing account is always first in line.
    @ParameterizedTest
    @ValueSource(strings = {"Devices", "dev ices", "1devices", "_devices", "-devices", "dev.ices", "dev/ices", "dévices", "devices,,usage"})
    void requiredParticipants_nameTheTableWouldReject_stopsTheStartUp(String names) {

        ConfigAssertions.assertRefused(runner, "app.user.deletion.required-participants=" + names, "must match");

    }

    @Test
    void requiredParticipants_nameLongerThanTheTableAllows_stopsTheStartUp() {

        ConfigAssertions.assertAccepted(runner, "app.user.deletion.required-participants=" + "a".repeat(63));
        ConfigAssertions.assertRefused(runner, "app.user.deletion.required-participants=" + "a".repeat(64), "must match");

    }

    // The primary key of user_deletion_participants is (user_id, participant): a name listed twice would fail every
    // promotion.
    @Test
    void requiredParticipants_listedTwice_stopsTheStartUp() {

        runner.withPropertyValues("app.user.deletion.required-participants=devices,usage,devices").run(context -> {

            assertThat(context).hasFailed();
            assertThat(context.getStartupFailure()).hasStackTraceContaining("must not list a participant twice");

        });

    }
}
