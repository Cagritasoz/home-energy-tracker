package com.cagritasoz.user_service.kafka;

import org.apache.kafka.clients.CommonClientConfigs;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.config.SslConfigs;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.kafka.autoconfigure.DefaultKafkaProducerFactoryCustomizer;
import org.springframework.boot.kafka.autoconfigure.KafkaConnectionDetails;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.boot.kafka.autoconfigure.SslBundleSslEngineFactory;
import org.springframework.boot.ssl.SslBundle;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.support.ProducerListener;
import org.springframework.util.StringUtils;

import java.util.Map;

// The service has two Kafka producers, each a KafkaTemplate over a ProducerFactory of its own, injected by name:
//
//   relayKafkaTemplate        publishes the outbox rows (OutboxBatchPublisher), over relayProducerFactory.
//   deadLetterKafkaTemplate   publishes the records the consumer could not apply to a dead-letter topic
//                             (KafkaConsumerConfig), over deadLetterProducerFactory.
//
// Spring Boot auto-configures one KafkaTemplate and one ProducerFactory and backs off from each as soon as the context has
// a bean of that type (ConditionalOnMissingBean). With two producers that is intended: they are all declared here, so no
// bean of Boot's exists, and a bean that needs one picks it with @Qualifier.
//
// Both factories are built the way Boot builds its own: spring.kafka.producer.* (application.yaml, what every producer of
// the service shares), then the client id of the producer (app.kafka.relay-producer.client-id and
// app.kafka.dead-letter-producer.client-id, bound by KafkaClientIdProperties), then what the connection details say about the
// connection (the bootstrap servers, security protocol and SSL bundle: this is how a Testcontainers @ServiceConnection or
// a platform points the producer at its broker, and spring.kafka.producer.* alone would miss it), then Boot's
// DefaultKafkaProducerFactoryCustomizer beans (the Micrometer client metrics).
//
// The two differ only in the client id, on purpose: the durability settings (acks=all, idempotence) belong to every
// producer of the service, and the relay's fail-fast timeouts suit a dead-letter send too, because a send that fails
// leaves the record unrecovered and the error handler tries it again. Each producer needs its own client id because the
// client id names the producer's metrics (a client id registered twice in one JVM collides) and shows in the broker's
// logs and quotas. To give one of them settings of its own later, put them in properties of its own and add them to the
// map in its factory method, before the shared builder creates the factory.
//
// The factories are beans, so Spring closes them (and their producers) with the application context.
@Configuration
@EnableConfigurationProperties(KafkaClientIdProperties.class)
public class KafkaProducerConfig {

    @Bean
    ProducerFactory<String, String> relayProducerFactory(KafkaClientIdProperties clientIds,
                                                         KafkaProperties properties,
                                                         KafkaConnectionDetails connectionDetails,
                                                         ObjectProvider<DefaultKafkaProducerFactoryCustomizer> customizers) {

        return producerFactory(clientIds.relayProducer().clientId(), properties, connectionDetails, customizers);

    }

    @Bean
    ProducerFactory<String, String> deadLetterProducerFactory(KafkaClientIdProperties clientIds,
                                                              KafkaProperties properties,
                                                              KafkaConnectionDetails connectionDetails,
                                                              ObjectProvider<DefaultKafkaProducerFactoryCustomizer> customizers) {

        return producerFactory(clientIds.deadLetterProducer().clientId(), properties, connectionDetails, customizers);

    }

    private static ProducerFactory<String, String> producerFactory(String clientId,
                                                                   KafkaProperties properties,
                                                                   KafkaConnectionDetails connectionDetails,
                                                                   ObjectProvider<DefaultKafkaProducerFactoryCustomizer> customizers) {

        Map<String, Object> producerProperties = properties.buildProducerProperties();

        producerProperties.put(ProducerConfig.CLIENT_ID_CONFIG, clientId);

        KafkaConnectionDetails.Configuration connection = connectionDetails.getProducer();
        producerProperties.put(CommonClientConfigs.BOOTSTRAP_SERVERS_CONFIG, connection.getBootstrapServers());

        if (StringUtils.hasLength(connection.getSecurityProtocol())) {
            producerProperties.put(CommonClientConfigs.SECURITY_PROTOCOL_CONFIG, connection.getSecurityProtocol());
        }

        if (connection.getSslBundle() != null) {
            producerProperties.put(SslConfigs.SSL_ENGINE_FACTORY_CLASS_CONFIG, SslBundleSslEngineFactory.class);
            producerProperties.put(SslBundle.class.getName(), connection.getSslBundle());
        }

        DefaultKafkaProducerFactory<String, String> factory = new DefaultKafkaProducerFactory<>(producerProperties);
        customizers.orderedStream().forEach(customizer -> customizer.customize(factory));

        return factory;

    }

    @Bean
    KafkaTemplate<String, String> relayKafkaTemplate(@Qualifier("relayProducerFactory") ProducerFactory<String, String> producerFactory,
                                                     ProducerListener<Object, Object> producerListener) {

        return template(producerFactory, producerListener);

    }

    @Bean
    KafkaTemplate<String, String> deadLetterKafkaTemplate(@Qualifier("deadLetterProducerFactory") ProducerFactory<String, String> producerFactory,
                                                          ProducerListener<Object, Object> producerListener) {

        return template(producerFactory, producerListener);

    }

    // Replaces Spring Boot's default LoggingProducerListener, which logs every failed send at ERROR with
    // the record's key AND payload ("Exception thrown when sending a message with key='...' and
    // payload='{...}'"). The payload of a user event holds the user's email and display name, which must
    // never reach a log, and a failed tick would write one such line, with a stack trace, for each of up
    // to 50 records. The relay already reports a failed send once, with ids only: a WARN naming the row's
    // seq and event id (OutboxBatchPublisher), the row's last_error, and the outbox.batches.failed counter.
    // ProducerListener's methods are all no-ops by default, so an empty implementation is a silent listener.
    //
    // It is for both producers: a KafkaTemplate built by hand defaults to LoggingProducerListener, and the dead-letter
    // template republishes records that can hold the same personal data.
    @Bean
    ProducerListener<Object, Object> kafkaProducerListener() {

        return new ProducerListener<>() {
        };

    }

    private static KafkaTemplate<String, String> template(ProducerFactory<String, String> producerFactory,
                                                          ProducerListener<Object, Object> producerListener) {

        KafkaTemplate<String, String> template = new KafkaTemplate<>(producerFactory);

        // The listener is typed for any key and value (it ignores both), the template for strings.
        @SuppressWarnings({"unchecked", "rawtypes"})
        ProducerListener<String, String> listener = (ProducerListener) producerListener;
        template.setProducerListener(listener);

        return template;

    }
}
