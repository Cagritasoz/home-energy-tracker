package com.cagritasoz.usage_service.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    // Re-declared here too (idempotent no-op if it already exists) so usage-service works
    // standalone even if started before ingestion-service ever has.
    @Bean(name = "energyUsageTopic")
    public NewTopic energyUsageTopic(@Value("${app.kafka.topic.energy-usage}") String topicName) {
        // Partition caps this consumer group at exactly one active listener thread -
        // raising spring.kafka.listener.concurrency wouldn't add throughput today, since a
        // second consumer thread would just sit idle with no partition to be assigned. Fine at
        // current volume; revisit partition count together with concurrency if that changes.
        return TopicBuilder.name(topicName)
                .partitions(1)
                .replicas(1)
                .build();
    }

    // usage-service is the natural owner of its own dead-letter topic, since it's the one
    // producing to it (see KafkaErrorHandlingConfig). Suffix is "-dlt", not ".DLT" - verified
    // against DeadLetterPublishingRecoverer.DEFAULT_DESTINATION_RESOLVER's actual source in
    // spring-kafka 4.1.1 (cr.topic() + "-dlt") rather than assumed; the ".DLT" convention some
    // older docs/examples reference is not what this version's default resolver produces.
    @Bean(name = "energyUsageDeadLetterTopic")
    public NewTopic energyUsageDeadLetterTopic(@Value("${app.kafka.topic.energy-usage}") String topicName) {
        return TopicBuilder.name(topicName + "-dlt")
                .partitions(1)
                .replicas(1)
                .build();
    }
}
