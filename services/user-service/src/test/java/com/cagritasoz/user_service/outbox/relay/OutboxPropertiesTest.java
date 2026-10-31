package com.cagritasoz.user_service.outbox.relay;

import com.cagritasoz.user_service.testsupport.config.ConfigAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

// app.outbox.* as the application binds it, from the real application.yaml with one setting changed.
class OutboxPropertiesTest {

    @EnableConfigurationProperties(OutboxProperties.class)
    static class Enable {
    }

    private final ApplicationContextRunner runner = ConfigAssertions.runnerFor(Enable.class);

    @Test
    void applicationYaml_bindsEverySetting() {

        runner.run(context -> {

            OutboxProperties properties = context.getBean(OutboxProperties.class);

            assertThat(properties.relay()).isEqualTo(
                    new OutboxProperties.Relay(2000, 50, 5000, 10, new OutboxProperties.Relay.Backoff(4000, 30000)));
            assertThat(properties.purge()).isEqualTo(new OutboxProperties.Purge(7, 1000, "0 0 3 * * *"));

        });

    }

    @Test
    void unknownKey_stopsTheStartUp() {

        ConfigAssertions.assertUnknownKeyRefused(runner, "app.outbox.relay.batch-sise=10");

    }

    @ParameterizedTest
    @ValueSource(strings = {"interval-ms", "batch-size", "send-timeout-ms", "max-batches-per-tick", "backoff.initial-ms", "backoff.max-ms"})
    void relay_zeroOrNegative_stopsTheStartUp(String key) {

        ConfigAssertions.assertRefused(runner, "app.outbox.relay." + key + "=0", "Positive");
        ConfigAssertions.assertRefused(runner, "app.outbox.relay." + key + "=-1", "Positive");

    }

    @ParameterizedTest
    @ValueSource(strings = {"retention-days", "batch-size"})
    void purge_zeroOrNegative_stopsTheStartUp(String key) {

        ConfigAssertions.assertRefused(runner, "app.outbox.purge." + key + "=0", "Positive");
        ConfigAssertions.assertRefused(runner, "app.outbox.purge." + key + "=-1", "Positive");

    }

    @Test
    void purge_blankCron_stopsTheStartUp() {

        ConfigAssertions.assertRefused(runner, "app.outbox.purge.cron= ", "NotBlank");

    }

    // Spring's marker for a disabled schedule, which the integration tests use to keep the purge quiet.
    @Test
    void purge_cronDash_isAccepted() {

        ConfigAssertions.assertAccepted(runner, "app.outbox.purge.cron=-");

    }
}
