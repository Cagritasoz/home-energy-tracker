package com.cagritasoz.user_service.testsupport.kafka;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.apache.kafka.common.serialization.StringSerializer;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

// A plain Kafka client for tests that play another service: it produces a message exactly as a participant would, and
// it reads a whole topic from the beginning to see what really landed there (the dead-letter topic, the events the
// relay published). No Spring, no consumer group: nothing here can interfere with the application's own consumer.
public final class KafkaTestClient {

    private final String bootstrapServers;

    public KafkaTestClient(String bootstrapServers) {
        this.bootstrapServers = bootstrapServers;
    }

    // Sends one record and waits for the broker's acknowledgement.
    public void send(String topic, String key, String value) {

        Properties props = new Properties();
        props.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, StringSerializer.class.getName());
        props.put(ProducerConfig.ACKS_CONFIG, "all");

        try (KafkaProducer<String, String> producer = new KafkaProducer<>(props)) {

            producer.send(new ProducerRecord<>(topic, key, value)).get(30, TimeUnit.SECONDS);

        }
        catch (InterruptedException e) {

            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);

        }
        catch (ExecutionException | TimeoutException e) {

            throw new IllegalStateException("Could not send to " + topic, e);

        }

    }

    // Everything on the topic right now, all partitions, from the beginning. An empty list when the topic does not
    // exist (yet). Per-partition order is kept; across partitions there is none.
    public List<ConsumerRecord<String, String>> readAll(String topic) {

        Properties props = new Properties();
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, bootstrapServers);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class.getName());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "test-reader-" + UUID.randomUUID());
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");
        // Never create a topic just by looking at it: "not there" must stay distinguishable from "empty".
        props.put(ConsumerConfig.ALLOW_AUTO_CREATE_TOPICS_CONFIG, "false");

        List<ConsumerRecord<String, String>> records = new ArrayList<>();

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {

            List<PartitionInfo> infos = consumer.listTopics(Duration.ofSeconds(10)).get(topic);

            if (infos == null || infos.isEmpty()) {

                return records;

            }

            List<TopicPartition> partitions = infos.stream().map(info -> new TopicPartition(topic, info.partition())).toList();

            consumer.assign(partitions);
            consumer.seekToBeginning(partitions);

            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(20);

            while (!reachedEnd(consumer, end) && System.nanoTime() < deadline) {

                ConsumerRecords<String, String> polled = consumer.poll(Duration.ofMillis(200));
                polled.forEach(records::add);

            }

        }

        return records;

    }

    private static boolean reachedEnd(KafkaConsumer<String, String> consumer, Map<TopicPartition, Long> end) {

        return end.entrySet().stream().allMatch(entry -> consumer.position(entry.getKey()) >= entry.getValue());

    }
}
