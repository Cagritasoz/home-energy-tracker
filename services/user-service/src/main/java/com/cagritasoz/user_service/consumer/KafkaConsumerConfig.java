package com.cagritasoz.user_service.consumer;

import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.dao.DataAccessException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.ExponentialBackOff;
import org.springframework.util.backoff.FixedBackOff;

// What happens to a record whose listener threw. Spring Boot hands the one CommonErrorHandler bean it finds to the
// listener container, so declaring it here is all the wiring there is.
//
// Three kinds of failure, treated differently:
//
//   Poison (PoisonEventException): the record can never be applied. No retry; it is published to "<topic>.dlt" and the
//     consumer moves on, so one bad record cannot block the partition behind it.
//
//   The database is the problem (DataAccessException anywhere in the cause chain): says nothing about the record, so it
//     is retried for as long as it takes, with a growing pause (1 s doubling to 30 s) - and it NEVER goes to the
//     dead-letter topic. Skipping the record would lose an event, or apply a later one first, and break the order of
//     that user's events. The consumer simply waits for the database to come back.
//
//   Anything else (a bug, an unexpected exception): three more attempts a second apart, then the dead-letter topic, so
//     a record that trips a bug is parked for a human instead of retried forever.
//
// The dead-letter topic is "<topic>.dlt" (Spring's own default is "<topic>-dlt"), created by infra/kafka/kafka-topics.sh.
// Partition -1 lets the producer pick the partition from the key, so the record does not have to land on the partition
// it came from (Spring's default would use the same partition number, which a one-partition dead-letter topic does not
// have). The record is published by the dead-letter template (deadLetterKafkaTemplate in KafkaProducerConfig), a producer
// of its own, with the same key and the original string value (the consumer reads strings, so what was received is exactly what is
// republished), and headers naming the failure and the origin.
@Configuration
public class KafkaConsumerConfig {

    private static final long DATABASE_FIRST_PAUSE_MS = 1_000;

    private static final long DATABASE_MAX_PAUSE_MS = 30_000;

    @Bean
    DefaultErrorHandler kafkaConsumerErrorHandler(@Qualifier("deadLetterKafkaTemplate") KafkaTemplate<String, String> deadLetterKafkaTemplate) {

        DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(deadLetterKafkaTemplate,
                (record, exception) -> new TopicPartition(record.topic() + ".dlt", -1));

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, new FixedBackOff(1_000, 3));

        // The pause policy is chosen per failure; null means "use the default backoff given above".
        handler.setBackOffFunction((record, exception) -> causedByDatabase(exception) ? databaseBackOff() : null);

        // Poison is never retried (the handler looks at the whole cause chain).
        handler.addNotRetryableExceptions(PoisonEventException.class);

        return handler;

    }

    static boolean causedByDatabase(Throwable exception) {

        for (Throwable cause = exception; cause != null; cause = cause.getCause()) {

            if (cause instanceof DataAccessException) {

                return true;

            }

            if (cause.getCause() == cause) {

                break;

            }

        }

        return false;

    }

    // A fresh object per failure: an ExponentialBackOff execution is tracked per record by the handler, and the
    // unlimited attempts and elapsed time are the point.
    static BackOff databaseBackOff() {

        ExponentialBackOff backOff = new ExponentialBackOff(DATABASE_FIRST_PAUSE_MS, 2.0);
        backOff.setMaxInterval(DATABASE_MAX_PAUSE_MS);

        return backOff;

    }
}
