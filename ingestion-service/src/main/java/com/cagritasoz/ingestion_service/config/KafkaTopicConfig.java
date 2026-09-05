package com.cagritasoz.ingestion_service.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    // The Spring BEAN name "energyUsageTopic", is pinned explicitly rather than left to default to this method's name.
    // With only one NewTopic bean in the app, it makes no difference, but Spring falls back
    // to matching an injection point's name against candidate bean names once more than one bean
    // of the same type exists (Meaning if there are multiple beans of type NewTopic) an explicit name survives this method being renamed later; an
    // implicit one would silently change and could break that match. Using @Qualifier annotation to refer to this exact bean in the IngestionService is what I did.
    @Bean(name = "energyUsageTopic")
    public NewTopic energyUsageTopic(@Value("${app.kafka.topic.energy-usage}") String topicName) {
        return TopicBuilder.name(topicName)
                .partitions(1)
                .replicas(1)
                .build();
    }
}
