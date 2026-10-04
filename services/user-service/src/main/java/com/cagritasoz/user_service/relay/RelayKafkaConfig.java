package com.cagritasoz.user_service.relay;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.support.ProducerListener;

@Configuration
public class RelayKafkaConfig {

    // Replaces Spring Boot's default LoggingProducerListener, which logs every failed send at ERROR with
    // the record's key AND payload ("Exception thrown when sending a message with key='...' and
    // payload='{...}'"). The payload of a user event holds the user's email and display name, which must
    // never reach a log, and a failed tick would write one such line, with a stack trace, for each of up
    // to 50 records. The relay already reports a failed send once, with ids only: a WARN naming the row's
    // seq and event id (OutboxBatchPublisher), the row's last_error, and the outbox.batches.failed counter.
    // ProducerListener's methods are all no-ops by default, so an empty implementation is a silent listener.
    // Spring Boot's KafkaTemplate picks up whichever ProducerListener bean exists.
    @Bean
    ProducerListener<Object, Object> kafkaProducerListener() {

        return new ProducerListener<>() {
        };

    }
}
