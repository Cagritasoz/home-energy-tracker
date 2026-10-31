package com.cagritasoz.user_service.consumer;

import com.cagritasoz.user_service.testsupport.config.ConfigAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

// app.processed-events.* as the application binds it, from the real application.yaml with one setting changed.
class ProcessedEventsPropertiesTest {

    @EnableConfigurationProperties(ProcessedEventsProperties.class)
    static class Enable {
    }

    private final ApplicationContextRunner runner = ConfigAssertions.runnerFor(Enable.class);

    @Test
    void applicationYaml_bindsEverySetting() {

        runner.run(context -> assertThat(context.getBean(ProcessedEventsProperties.class).purge())
                .isEqualTo(new ProcessedEventsProperties.Purge(30, 1000, "0 30 3 * * *")));

    }

    @Test
    void unknownKey_stopsTheStartUp() {

        ConfigAssertions.assertUnknownKeyRefused(runner, "app.processed-events.purge.retention-dayz=10");

    }

    @ParameterizedTest
    @ValueSource(strings = {"retention-days", "batch-size"})
    void purge_zeroOrNegative_stopsTheStartUp(String key) {

        ConfigAssertions.assertRefused(runner, "app.processed-events.purge." + key + "=0", "Positive");
        ConfigAssertions.assertRefused(runner, "app.processed-events.purge." + key + "=-1", "Positive");

    }

    @Test
    void purge_blankCron_stopsTheStartUp() {

        ConfigAssertions.assertRefused(runner, "app.processed-events.purge.cron= ", "NotBlank");

    }
}
