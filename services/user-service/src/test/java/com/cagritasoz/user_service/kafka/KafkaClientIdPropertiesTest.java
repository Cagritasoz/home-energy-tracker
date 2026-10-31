package com.cagritasoz.user_service.kafka;

import com.cagritasoz.user_service.testsupport.config.ConfigAssertions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

// app.kafka.* as the application binds it, from the real application.yaml with one setting changed.
class KafkaClientIdPropertiesTest {

    @EnableConfigurationProperties(KafkaClientIdProperties.class)
    static class Enable {
    }

    private final ApplicationContextRunner runner = ConfigAssertions.runnerFor(Enable.class);

    @Test
    void applicationYaml_bindsBothClientIds() {

        runner.run(context -> {

            KafkaClientIdProperties properties = context.getBean(KafkaClientIdProperties.class);

            assertThat(properties.relayProducer().clientId()).isEqualTo("user-service-outbox-relay");
            assertThat(properties.deadLetterProducer().clientId()).isEqualTo("user-service-dead-letter");

        });

    }

    // Two producers sharing a client id would collide on their metrics, and a blank one would get a generated name.
    @ParameterizedTest
    @ValueSource(strings = {"relay-producer", "dead-letter-producer"})
    void blankClientId_stopsTheStartUp(String producer) {

        ConfigAssertions.assertRefused(runner, "app.kafka." + producer + ".client-id= ", "NotBlank");

    }

    @Test
    void unknownKey_stopsTheStartUp() {

        ConfigAssertions.assertUnknownKeyRefused(runner, "app.kafka.relay-producer.client-ide=x");

    }
}
