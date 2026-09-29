package com.cagritasoz.user_service.relay;

import com.cagritasoz.contracts.EventHeaders;
import com.cagritasoz.contracts.user.UserEvents;
import com.cagritasoz.user_service.entity.OutboxEvent;
import com.cagritasoz.user_service.repository.OutboxRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxRepository outboxRepository;

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${app.outbox.relay.batch-size}")
    private int batchSize;

    // One deadline for the whole batch, not per record: how long the relay waits for all acks while
    // it holds the advisory lock and a database connection.
    @Value("${app.outbox.relay.send-timeout-ms}")
    private long sendTimeoutMs;

    @Scheduled(fixedDelayString = "${app.outbox.relay.interval-ms}")
    @Transactional
    public void relay() {

        if(!outboxRepository.advisoryLockAcquired(7_777_777L)) {
            return;
        }

        List<OutboxEvent> events = outboxRepository.findByPublishedAtIsNullOrderBySeqAsc(PageRequest.of(0, batchSize));

        // Phase 1: hand every record to the producer before waiting for any of them.
        List<CompletableFuture<SendResult<String, String>>> futures = new ArrayList<>(events.size());

        for (OutboxEvent event : events) {

            try {
                // send() doesn't wait for the network: the scheduled thread serializes the key and value and
                // appends the record to the producer's accumulator, into the batch for its partition (chosen from
                // the key, so one user's events always share a partition), then returns at once. A separate
                // sender thread ships ready batches to the broker - typically this whole loop in one or two requests.
                // Records to the same partition keep this send() order (idempotent producer).
                futures.add(kafkaTemplate.send(toRecord(event)));
            }
            catch (RuntimeException e) {
                // send() itself can throw (e.g. no topic metadata within max.block.ms). Nothing after this row is
                // sent: it would only be marked unpublished and sent again anyway, behind this row.
                futures.add(CompletableFuture.failedFuture(e));
                break;
            }
        }

        // Phase 2: collect the outcomes in seq order and mark the published prefix.
        long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(sendTimeoutMs);

        for (int i = 0; i < futures.size(); i++) {

            OutboxEvent event = events.get(i);

            try {
                // The scheduled thread blocks here until the broker has acknowledged this record (acks=all), or the
                // batch deadline passes. Only an acknowledged row may be marked published - that is the outbox's
                // guarantee. Records later in the batch were sent concurrently, so most of them are already done.
                futures.get(i).get(Math.max(0, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            }
            catch (InterruptedException e) {
                // Shutdown, not a problem with this row: keep the interrupt flag and don't count an attempt.
                Thread.currentThread().interrupt();
                break;
            }
            catch (ExecutionException | TimeoutException e) {
                // ExecutionException wraps what the producer reported (timeout after delivery.timeout.ms,
                // RecordTooLargeException, ...); TimeoutException means our own batch deadline passed first.
                Throwable cause = e instanceof ExecutionException ? e.getCause() : e;
                outboxRepository.recordError(String.valueOf(cause), event.getSeq());
                log.warn("Outbox relay: send failed for seq {} (event {}), stopping this batch: {}",
                        event.getSeq(), event.getId(), cause.toString());
                // Rows after this one stay unpublished even if they were delivered: the next tick sends them again
                // after this row, so the latest state is also the last one in the log. Consumers absorb the duplicates.
                break;
            }

            // Outside the try: a database error here must roll the transaction back, not count as a failed send.
            outboxRepository.markPublished(event.getSeq());
        }
    }

    private ProducerRecord<String, String> toRecord(OutboxEvent event) {

        ProducerRecord<String, String> record = new ProducerRecord<>(UserEvents.TOPIC, event.getAggregateId(), event.getPayload());

        record.headers()
                .add(new RecordHeader(EventHeaders.EVENT_ID, event.getId().toString().getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader(EventHeaders.EVENT_TYPE, event.getEventType().getBytes(StandardCharsets.UTF_8)))
                .add(new RecordHeader(EventHeaders.SCHEMA_VERSION, String.valueOf(event.getSchemaVersion()).getBytes(StandardCharsets.UTF_8)));

        return record;
    }
}
