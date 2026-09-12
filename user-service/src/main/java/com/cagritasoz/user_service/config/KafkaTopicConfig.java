package com.cagritasoz.user_service.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

// One topic for every event user-service publishes, covering both aggregates (USER, ALERT_RULE)
// Deliberately not split by aggregate type. "user-domain-events" is a better name then "user-events":
// AlertRule is a child of the User aggregate/domain (no independent REST existence, cascade-
// deletes with its user), not a peer - the name should say so rather than rely on a reader
// already knowing that. That cascade is invisible to the outbox-writing code (a DB-level
// ON DELETE CASCADE, not application code), so a UserDeleted event is the only signal a cache of
// alert-rule state will ever get for "this user's rules are gone too" - it depends on that event
// and the user's earlier AlertRule* events staying strictly ordered. Splitting into two topics
// would remove Kafka's within-partition ordering guarantee between them, opening a real race: a
// consumer could see UserDeleted first and then a stale, now-orphaned ALERT_RULE_CREATED after,
// with nothing left to ever clean it up. One topic keeps that structurally impossible - and
// OutboxEvent.partitionKey (always the owning user's id, never a rule's own id) is what keeps it
// impossible at ANY partition count, not just today's single partition.
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
