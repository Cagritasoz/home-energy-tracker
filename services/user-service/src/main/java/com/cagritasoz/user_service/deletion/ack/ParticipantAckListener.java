package com.cagritasoz.user_service.deletion.ack;

import com.cagritasoz.user_service.consumer.EventEnvelopeReader;
import com.cagritasoz.user_service.consumer.PoisonEventException;
import com.cagritasoz.user_service.consumer.ReceivedEvent;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;

// Reads the participants' confirmations (UserDevicesUnclaimed, and whatever later services add to ParticipantAcks)
// and records them against the deletion they answer.
//
// Not transactional itself: it only reads and routes. The transaction is ParticipantAckService's, a separate bean
// so that its @Transactional goes through Spring's proxy. A record is acknowledged to Kafka after this method returns
// without an exception (ack mode RECORD), i.e. after the service has committed; an exception leaves the offset where
// it is and hands the record to the error handler (KafkaConsumerConfig): poison goes to the dead-letter topic, a
// database problem is retried until it clears.
//
// The listener reads strings and parses them itself, so there is nothing a deserializer can fail on: whatever is wrong
// with a message surfaces here, as a PoisonEventException.
@Slf4j
@Component
public class ParticipantAckListener {

    private final ParticipantAcks acks;

    private final EventEnvelopeReader reader;

    private final ParticipantAckService service;

    private final MeterRegistry meterRegistry;

    public ParticipantAckListener(ParticipantAcks acks,
                                  EventEnvelopeReader reader,
                                  ParticipantAckService service,
                                  MeterRegistry meterRegistry) {
        this.acks = acks;
        this.reader = reader;
        this.service = service;
        this.meterRegistry = meterRegistry;
    }

    // idIsGroup = false: the consumer group is the one from spring.kafka.consumer.group-id (user-service) in application.yaml, not the
    // listener's id. autoStartup lets a test context without a broker keep the listener from starting.
    @KafkaListener(
            id = "participant-acks",
            idIsGroup = false,
            topics = "#{@participantAcks.topics()}", // Spring Expression Language (SpEL)
            autoStartup = "${app.user.deletion.ack-consumer.enabled:true}") // set to true, IDE displays false for some reason
    public void onRecord(ConsumerRecord<String, String> record) {

        handle(record.topic(), record.value());

    }

    // Split from onRecord so a test can hand it a topic and a value without building a Kafka record.
    void handle(String topic, String value) {

        ReceivedEvent event = reader.read(value);

        Optional<ParticipantAck> ack = acks.find(topic, event.eventType());

        if (ack.isEmpty()) {

            // Another event type on the same topic (a producer added one): not a confirmation, not an error. A
            // service must survive its producers' new event types.
            log.debug("Ignoring event type {} on {}: not a participant confirmation", event.eventType(), topic);

            return;

        }

        ParticipantAck known = ack.get();

        // A shape this service does not understand. Applying it as the version it knows could misread it: loud failure.
        if (event.schemaVersion() != known.schemaVersion()) {

            throw new PoisonEventException("Unsupported schemaVersion " + event.schemaVersion() + " of " + event.eventType()
                    + " (supported: " + known.schemaVersion() + ")");

        }

        UUID userId;

        try {

            userId = UUID.fromString(event.aggregateId());

        }
        catch (IllegalArgumentException e) {

            throw new PoisonEventException("The aggregateId of " + event.eventType() + " is not a user id");

        }

        ParticipantAckService.Outcome outcome = service.acknowledge(event, userId, known);

        // Counted after the transaction committed: an exception above means nothing happened and nothing is counted.
        Counter.builder("user.deletion.participant.acks")
                .description("Participant confirmations processed, by participant and outcome")
                .tag("participant", known.participant())
                .tag("outcome", outcome.name().toLowerCase(Locale.ROOT))
                .register(meterRegistry)
                .increment();

        if (outcome == ParticipantAckService.Outcome.ACKED) {

            // Ids only: the user's and the participant's name.
            log.info("Participant {} confirmed the cleanup for user {}", known.participant(), userId);

        }

    }
}
