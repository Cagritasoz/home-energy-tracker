package com.cagritasoz.user_service.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

// One topic for every event user-service publishes. "user-domain-events", not "user-events": the
// name should describe the domain this service owns, not just the one aggregate type (USER) that
// happens to be the only one today - alert rules used to be a second aggregate under this same
// domain before they moved out into their own service, and this service may own more than one
// aggregate type again in the future.
@Configuration
public class KafkaTopicConfig {

    // Partition count 1 for now, per current volume - safe to raise later purely for throughput/
    // fault-tolerance without reopening the ordering question, because partitionKey (not
    // aggregate_id) already keys every one of a user's events to the same partition regardless of
    // how many partitions the topic has.
    @Bean(name = "userDomainTopic")
    public NewTopic userDomainTopic(@Value("${app.kafka.topic.user-domain}") String topicName) {
        return TopicBuilder.name(topicName)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
