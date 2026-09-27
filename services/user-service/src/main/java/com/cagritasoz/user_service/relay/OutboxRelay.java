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
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Slf4j
@Component
@RequiredArgsConstructor
public class OutboxRelay {

    private final OutboxRepository outboxRepository;

    private final KafkaTemplate<String, String> kafkaTemplate;

    @Value("${app.outbox.relay.batch-size}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${app.outbox.relay.interval-ms}")
    @Transactional
    public void relay() {

        if(!outboxRepository.advisoryLockAcquired(7_777_777L)) {
            return;
        }

        List<OutboxEvent> events = outboxRepository.findByPublishedAtIsNullOrderBySeqAsc(PageRequest.of(0, batchSize));

        for(OutboxEvent event : events) {

            ProducerRecord<String, String> record = new ProducerRecord<>(UserEvents.TOPIC, event.getAggregateId(), event.getPayload());

            record.headers()
                    .add(new RecordHeader(EventHeaders.EVENT_ID, event.getId().toString().getBytes(StandardCharsets.UTF_8)))
                    .add(new RecordHeader(EventHeaders.EVENT_TYPE, event.getEventType().getBytes(StandardCharsets.UTF_8)))
                    .add(new RecordHeader(EventHeaders.SCHEMA_VERSION, String.valueOf(event.getSchemaVersion()).getBytes(StandardCharsets.UTF_8)));

            try {
                kafkaTemplate.send(record).get(5, TimeUnit.SECONDS);
                outboxRepository.markPublished(event.getSeq());
            }
            catch (Exception e) {
                outboxRepository.recordError(e.toString(), event.getSeq());
                break;
            }
        }

    }
}
