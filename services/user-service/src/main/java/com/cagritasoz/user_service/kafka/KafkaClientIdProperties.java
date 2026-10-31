package com.cagritasoz.user_service.kafka;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

// app.kafka.* (explained in application.yaml): the client id of each producer. The rest of the producers' settings are
// Spring Boot's spring.kafka.producer.*.
@Validated
@ConfigurationProperties(prefix = "app.kafka", ignoreUnknownFields = false)
public record KafkaClientIdProperties(@NotNull @Valid Producer relayProducer, @NotNull @Valid Producer deadLetterProducer) {

    public record Producer(@NotBlank String clientId) {
    }
}
