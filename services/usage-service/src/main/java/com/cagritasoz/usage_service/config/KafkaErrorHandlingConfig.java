package com.cagritasoz.usage_service.config;

import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.kafka.autoconfigure.KafkaProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.DefaultKafkaProducerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.core.ProducerFactory;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

import java.util.Map;

@Configuration
public class KafkaErrorHandlingConfig {

    // Raw byte[] producer, deliberately separate from the JSON/type-mapping contract used for
    // consuming: when ErrorHandlingDeserializer catches a deserialization failure (malformed
    // JSON, unmapped __TypeId__), it's the ORIGINAL undecoded bytes - not a re-serialized object
    // - that DeadLetterPublishingRecoverer needs to republish.
    @Bean
    public ProducerFactory<byte[], byte[]> dlqProducerFactory(KafkaProperties kafkaProperties) {
        Map<String, Object> producerProps = kafkaProperties.buildProducerProperties();
        producerProps.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        producerProps.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
        return new DefaultKafkaProducerFactory<>(producerProps);
    }

    // Nothing in this class ever calls send() on this template directly - it's handed
    // to DeadLetterPublishingRecoverer below, which does the actual publish (destination topic
    // name, DLT_* failure headers, the record itself) internally. Wiring a tool, not
    // driving it by hand.
    @Bean
    public KafkaTemplate<byte[], byte[]> dlqKafkaTemplate(ProducerFactory<byte[], byte[]> dlqProducerFactory) {
        return new KafkaTemplate<>(dlqProducerFactory);
    }

    // Spring Boot's autoconfigured listener container factory auto-detects the single
    // CommonErrorHandler bean in the context and wires it in - no manual factory bean needed.
    //
    // Covers two distinct failure classes with the same mechanism: a Kafka-level deserialization
    // failure (poison message) via ErrorHandlingDeserializer below, and a transient InfluxDB
    // write failure thrown out of UsageService's listener body (deliberately not caught there).
    // Both get retried per the backoff, then routed to energy-usage-events-dlt if still failing.
    // Business-level "unknown deviceId" is NOT one of these - that's handled by tagging the
    // point instead (see UsageService), never by throwing/dead-lettering.
    //
    // (free, not configured by us): DefaultErrorHandler already treats
    // ErrorHandlingDeserializer's failures as non-retryable by default - a poison message skips
    // the backoff wait entirely and goes straight to the recoverer, while our InfluxDB write
    // exceptions (not in that built-in list) get the full retry treatment below. Correct
    // behavior for both cases without us having to say so explicitly.
    //
    // Gaps worth knowing, not fixed here: (1) no exception classification - a permanently broken
    // write (e.g. a bug that always produces an invalid Point) retries the same 2 times as a
    // genuinely transient one before giving up; DefaultErrorHandler#addNotRetryableExceptions
    // would let a known-permanent failure skip straight to the DLQ instead of wasting attempts.
    // (2) FixedBackOff's wait blocks this container's single consumer thread - the whole topic
    // stops draining during a retry sleep, not just the failing record's partition. (3) nothing
    // reads energy-usage-events-dlt once records land there - fine for now, but a real system
    // would alert on its depth or run a reprocessing job against it instead of it being silent.
    @Bean
    public DefaultErrorHandler kafkaErrorHandler(
            KafkaTemplate<byte[], byte[]> dlqKafkaTemplate,
            @Value("${app.kafka.retry.max-attempts}") long maxAttempts,
            @Value("${app.kafka.retry.interval-ms}") long intervalMs) {

        // Default destination is computed per failed record as <original topic> + "-dlt" (plain
        // string concat, not a lookup against anything we've declared) - matches
        // energyUsageDeadLetterTopic below by design, not because this reads that bean. Would
        // silently collide with any unrelated topic that happens to be named exactly
        // "energy-usage-events-dlt" already; pass a custom BiFunction destination resolver here
        // instead of relying on the default if that's ever a real risk.
        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(dlqKafkaTemplate);
        FixedBackOff backOff = new FixedBackOff(intervalMs, maxAttempts);
        return new DefaultErrorHandler(recoverer, backOff);
    }
}
