package com.cagritasoz.user_service.kafka;

import com.cagritasoz.user_service.consumer.KafkaConsumerConfig;
import com.cagritasoz.user_service.outbox.OutboxRepository;
import com.cagritasoz.user_service.outbox.relay.OutboxBatchPublisher;
import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.config.SslConfigs;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.SslBundleSslEngineFactory;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.boot.test.context.ConfigDataApplicationContextInitializer;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.LoggingProducerListener;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.List;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

// The two producers of the service in a real application context: Spring Boot's Kafka auto-configuration and
// KafkaProducerConfig, nothing else (no broker: a producer connects on its first send).
//
// What it guards: with two producers Boot's own KafkaTemplate and ProducerFactory must be gone, the two must differ only
// in their client id, both must carry what Boot's factory carries (connection details, customizers) and the silent
// listener, and each user must get the right one by name.
class KafkaProducersWiringTest {

    private final ApplicationContextRunner withoutClientIds = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(KafkaAutoConfiguration.class))
            .withUserConfiguration(KafkaProducerConfig.class)
            .withPropertyValues(
                    "spring.kafka.bootstrap-servers=localhost:1",
                    "spring.kafka.producer.acks=all",
                    "spring.kafka.producer.properties.delivery.timeout.ms=5000");

    private final ApplicationContextRunner runner = withoutClientIds.withPropertyValues(
            "app.kafka.relay-producer.client-id=relay-client",
            "app.kafka.dead-letter-producer.client-id=dead-letter-client");

    @Test
    void theContextHasTheTwoTemplatesAndTheirFactories_andNoneOfBoots() {

        runner.run(context -> {

            assertThat(context).hasNotFailed();
            assertThat(context.getBeansOfType(KafkaTemplate.class).keySet())
                    .containsExactlyInAnyOrder("relayKafkaTemplate", "deadLetterKafkaTemplate");
            assertThat(context.getBeansOfType(ProducerFactory.class).keySet())
                    .containsExactlyInAnyOrder("relayProducerFactory", "deadLetterProducerFactory");

        });

    }

    @Test
    void theRelayHasItsOwnClientIdAndTheSharedSettings() {

        runner.run(context -> {

            assertThat(configurationOf(context.getBean("relayKafkaTemplate", KafkaTemplate.class)))
                    .containsEntry(ProducerConfig.CLIENT_ID_CONFIG, "relay-client")
                    .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                    .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "5000");

        });

    }

    // The yaml says not to set spring.kafka.producer.client-id because the config overwrites it: a stale one must change
    // neither producer.
    @Test
    void aClientIdInTheSharedSettingsIsOverwrittenForBothProducers() {

        runner.withPropertyValues("spring.kafka.producer.client-id=stale-shared-client")
                .run(context -> {

                    assertThat(configurationOf(context.getBean("relayKafkaTemplate", KafkaTemplate.class)))
                            .containsEntry(ProducerConfig.CLIENT_ID_CONFIG, "relay-client");
                    assertThat(configurationOf(context.getBean("deadLetterKafkaTemplate", KafkaTemplate.class)))
                            .containsEntry(ProducerConfig.CLIENT_ID_CONFIG, "dead-letter-client");

                });

    }

    // Both client ids are required: a missing one fails the start-up instead of giving a producer a generated name.
    @Test
    void aMissingClientIdFailsTheStartUp() {

        withoutClientIds.withPropertyValues("app.kafka.dead-letter-producer.client-id=dead-letter-client")
                .run(context -> assertThat(context).hasFailed());

        withoutClientIds.withPropertyValues("app.kafka.relay-producer.client-id=relay-client")
                .run(context -> assertThat(context).hasFailed());

    }

    @Test
    void theDeadLetterProducerHasAnotherClientIdAndTheSameSettings() {

        runner.run(context -> {

            KafkaTemplate<?, ?> relay = context.getBean("relayKafkaTemplate", KafkaTemplate.class);
            KafkaTemplate<?, ?> deadLetter = context.getBean("deadLetterKafkaTemplate", KafkaTemplate.class);

            assertThat(deadLetter).isNotSameAs(relay);
            assertThat(deadLetter.getProducerFactory()).isNotSameAs(relay.getProducerFactory());

            assertThat(configurationOf(deadLetter))
                    .containsEntry(ProducerConfig.CLIENT_ID_CONFIG, "dead-letter-client")
                    .containsEntry(ProducerConfig.ACKS_CONFIG, "all")
                    .containsEntry(ProducerConfig.DELIVERY_TIMEOUT_MS_CONFIG, "5000")
                    .containsEntry(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, configurationOf(relay).get(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG))
                    .containsEntry(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, configurationOf(relay).get(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG))
                    .containsEntry(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, configurationOf(relay).get(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG));

            assertThat(configurationOf(relay)).containsEntry(ProducerConfig.CLIENT_ID_CONFIG, "relay-client");

        });

    }

    // A Testcontainers @ServiceConnection (or a platform) supplies the broker address as connection details, which
    // spring.kafka.bootstrap-servers does not see: the details win, and both producers use them.
    @Test
    void theConnectionDetailsWinOverTheProperties_forBothProducers() {

        runner.withBean(KafkaConnectionDetails.class, () -> () -> List.of("details-broker:9092"))
                .run(context -> {

                    assertThat(configurationOf(context.getBean("relayKafkaTemplate", KafkaTemplate.class)))
                            .containsEntry(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, List.of("details-broker:9092"));
                    assertThat(configurationOf(context.getBean("deadLetterKafkaTemplate", KafkaTemplate.class)))
                            .containsEntry(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, List.of("details-broker:9092"));

                });

    }

    @Test
    void theSecurityProtocolAndTheSslBundleOfTheConnectionDetailsReachBothProducers() {

        SslBundle bundle = mock(SslBundle.class);

        KafkaConnectionDetails secured = new KafkaConnectionDetails() {

            @Override
            public List<String> getBootstrapServers() {
                return List.of("secured-broker:9093");
            }

            @Override
            public Configuration getProducer() {
                return Configuration.of(List.of("secured-broker:9093"), bundle, "SSL");
            }
        };

        runner.withBean(KafkaConnectionDetails.class, () -> secured)
                .run(context -> {

                    for (String name : List.of("relayKafkaTemplate", "deadLetterKafkaTemplate")) {

                        assertThat(configurationOf(context.getBean(name, KafkaTemplate.class)))
                                .as(name)
                                .containsEntry(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, "SSL")
                                .containsEntry(SslConfigs.SSL_ENGINE_FACTORY_CLASS_CONFIG, SslBundleSslEngineFactory.class)
                                .containsEntry(SslBundle.class.getName(), bundle);

                    }

                });

    }

    // What Boot adds to its own factory (the Micrometer client metrics are such a customizer), both factories have too.
    @Test
    void theFactoryCustomizersAreAppliedToBothFactories() {

        ProducerFactory.Listener<String, String> listener = new ProducerFactory.Listener<>() {
        };

        DefaultKafkaProducerFactoryCustomizer customizer = factory -> {
            @SuppressWarnings("unchecked")
            DefaultKafkaProducerFactory<String, String> typed = (DefaultKafkaProducerFactory<String, String>) factory;
            typed.addListener(listener);
        };

        runner.withBean(DefaultKafkaProducerFactoryCustomizer.class, () -> customizer)
                .run(context -> {

                    assertThat(context.getBean("relayProducerFactory", ProducerFactory.class).getListeners()).contains(listener);
                    assertThat(context.getBean("deadLetterProducerFactory", ProducerFactory.class).getListeners()).contains(listener);

                });

    }

    // Neither template may use Spring's LoggingProducerListener: it logs the payload of a failed send.
    @Test
    void bothTemplatesUseTheSilentProducerListener() {

        runner.run(context -> {

            ProducerListener<?, ?> silent = context.getBean("kafkaProducerListener", ProducerListener.class);

            assertThat(silent).isNotInstanceOf(LoggingProducerListener.class);
            assertThat(ReflectionTestUtils.getField(context.getBean("relayKafkaTemplate"), "producerListener")).isSameAs(silent);
            assertThat(ReflectionTestUtils.getField(context.getBean("deadLetterKafkaTemplate"), "producerListener")).isSameAs(silent);

        });

    }

    // The outbox relay publishes with the relay's template and nothing else.
    @Test
    void theOutboxPublisherGetsTheRelaysTemplate() {

        runner.withBean(OutboxRepository.class, () -> mock(OutboxRepository.class))
                .withUserConfiguration(OutboxBatchPublisher.class)
                .withInitializer(new ConfigDataApplicationContextInitializer()) // the rest of OutboxProperties, from application.yaml
                .run(context -> {

                    assertThat(ReflectionTestUtils.getField(context.getBean(OutboxBatchPublisher.class), "kafkaTemplate"))
                            .isSameAs(context.getBean("relayKafkaTemplate"));

                });

    }

    // The consumer's error handler dead-letters with the dead-letter template and never with the relay's.
    @Test
    void theConsumersErrorHandlerPublishesWithTheDeadLetterTemplate() {

        runner.withUserConfiguration(KafkaConsumerConfig.class)
                .run(context -> {

                    DefaultErrorHandler handler = context.getBean(DefaultErrorHandler.class);

                    Object tracker = ReflectionTestUtils.getField(handler, "failureTracker");
                    Object recoverer = ReflectionTestUtils.getField(tracker, "recoverer");

                    assertThat(recoverer).isInstanceOf(DeadLetterPublishingRecoverer.class);

                    @SuppressWarnings("unchecked")
                    Function<ProducerRecord<?, ?>, KafkaOperations<?, ?>> templateResolver =
                            (Function<ProducerRecord<?, ?>, KafkaOperations<?, ?>>) ReflectionTestUtils.getField(recoverer, "templateResolver");

                    assertThat(templateResolver.apply(new ProducerRecord<>("any.topic.dlt", "key", "value")))
                            .isSameAs(context.getBean("deadLetterKafkaTemplate"))
                            .isNotSameAs(context.getBean("relayKafkaTemplate"));

                });

    }

    private static java.util.Map<String, Object> configurationOf(KafkaTemplate<?, ?> template) {

        return template.getProducerFactory().getConfigurationProperties();

    }
}
