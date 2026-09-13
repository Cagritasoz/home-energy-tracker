package com.cagritasoz.user_service.service;

import com.cagritasoz.user_service.aspect.SkipLogging;
import com.cagritasoz.user_service.entity.OutboxEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.List;

// Reads outbox_events rows OutboxService wrote and publishes them to Kafka - the second half of
// the outbox pattern (see V4's header comment and OutboxService's class comment for the first
// half). A @Scheduled poller for now; Debezium/CDC is the noted future upgrade.
//
// Deliberately has NO OutboxEventRepository dependency and no @Transactional methods of its own -
// every persistence operation on outbox_events goes through OutboxService instead, called as a
// genuine cross-bean call (through OutboxService's Spring proxy). Doing the read or the
// published_at write directly here, on a method this class calls via plain "this.", would be the
// classic Spring AOP self-invocation trap: a self-call bypasses the proxy that actually starts a
// transaction, so @Transactional on it would silently never activate.
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    private final OutboxService outboxService;

    // Long key (partitionKey), String value - payload is already-serialized JSON text, sent
    // verbatim. Requires spring.kafka.producer.value-serializer=StringSerializer, NOT
    // JacksonJsonSerializer - re-serializing an already-stringified JSON payload through Jackson
    // would wrap it in an extra pair of escaped quotes instead of sending the JSON object itself.
    private final KafkaTemplate<Long, String> kafkaTemplate;

    @Value("${app.kafka.topic.user-domain}")
    private String topicName;

    @Value("${app.outbox.relay.batch-size}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${app.outbox.relay.interval-ms}")
    @SkipLogging
    public void relay() {

        List<OutboxEvent> pending = outboxService.findPendingEvents(batchSize);

        for (OutboxEvent event : pending) {

            // Strict id order, and stop entirely on the first failure rather than skipping past
            // it to the next row: partitionKey exists specifically so a user's events land on one
            // Kafka partition and stay ordered relative to each other. If a send failed here and
            // this loop moved on anyway, a LATER event for the same partitionKey could reach
            // Kafka before this EARLIER one - silently reversing the exact ordering guarantee
            // partitionKey exists for. Stopping means the next scheduled tick retries this same
            // row first, in the same order, every time - lost throughput during an outage, never
            // an ordering violation. A single poison row blocking the whole batch indefinitely is
            // the accepted downside - V4's deferred attempts/last_error columns are how you'd
            // eventually detect that specific case, not built yet.
            if (!publish(event)) {
                break;
            }
        }
    }

    // Split into two try blocks, not one, because the two failures they guard mean genuinely
    // different things and should never be logged identically: a failure in the first means
    // Kafka never got this event - it has not been delivered, and resending it next cycle is a
    // normal retry. A failure in the second means Kafka ALREADY confirmed the write and only the
    // local bookkeeping failed - the row stays "pending" and gets resent next cycle anyway, but
    // that resend is now a genuine DUPLICATE delivery, not a first attempt. This is the concrete
    // mechanism behind this system being at-least-once rather than exactly-once, and exactly why
    // every consumer of user-domain-events must be idempotent (see CLAUDE.md) - there is no way
    // to close this window without a distributed transaction spanning Postgres and Kafka, which
    // nothing here (or almost anything in practice) actually does.
    private boolean publish(OutboxEvent event) {

        ProducerRecord<Long, String> record =
                new ProducerRecord<>(topicName, event.getPartitionKey(), event.getPayload());

        // Headers, not the message body, are what a consumer filters on - readable without
        // deserializing the value at all. A consumer that only wants USER_DELETED checks this
        // ONE header and skips everything else before ever parsing JSON. This is the actual
        // mechanism behind "does the type id have to match the event name for filtering to be
        // fast": yes, and it has to live here, in a header - not inside the payload, or a
        // consumer would have to deserialize every record just to learn it didn't want it.
        record.headers().add(new RecordHeader("event-type",
                event.getEventType().name().getBytes(StandardCharsets.UTF_8)));
        record.headers().add(new RecordHeader("aggregate-type",
                event.getAggregateType().name().getBytes(StandardCharsets.UTF_8)));
        // Lets a consumer that ever needs explicit dedup (beyond the natural delete/upsert
        // idempotency already in place - see this project's outbox design notes) use this
        // outbox row's own id as a stable event identity, not just the Kafka offset.
        record.headers().add(new RecordHeader("outbox-event-id",
                event.getId().toString().getBytes(StandardCharsets.UTF_8)));

        // Blocking on purpose: this is a scheduled background job, not a caller waiting on an
        // HTTP response (unlike IngestionService's deliberately fire-and-forget send) -
        // published_at must only ever be set AFTER Kafka has actually confirmed the write.
        // Marking it published on a fire-and-forget send whose failure is only logged would
        // let a row that was never really delivered look exactly like one that was - the one
        // failure mode that would make this entire pattern pointless.
        try {
            kafkaTemplate.send(record).get(); // Blocked to ensure that broker acks.
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.error("Interrupted while sending outbox event {} (type {}) to Kafka - NOT delivered, will retry next cycle",
                    event.getId(), event.getEventType(), e);
            return false;
        } catch (Exception e) {
            log.error("Failed to send outbox event {} (type {}) to Kafka - NOT delivered, will retry next cycle",
                    event.getId(), event.getEventType(), e);
            return false;
        }

        try {
            outboxService.markPublished(event.getId());
            return true;
        } catch (Exception e) {
            log.error("Outbox event {} (type {}) WAS delivered to Kafka, but recording it as published failed - "
                    + "it WILL be resent next cycle as a duplicate, not lost. Consumers must be idempotent for exactly this reason.",
                    event.getId(), event.getEventType(), e);
            return false;
        }
    }
}
