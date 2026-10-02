package com.cagritasoz.user_service.relay;

import com.cagritasoz.contracts.user.UserEventType;
import com.cagritasoz.contracts.user.UserEvents;
import com.cagritasoz.user_service.entity.OutboxEvent;
import com.cagritasoz.user_service.repository.OutboxRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class OutboxRelayTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    private OutboxRelay outboxRelay;

    @BeforeEach
    void setUp() {

        outboxRelay = new OutboxRelay(outboxRepository, kafkaTemplate, 50, 5_000);

    }

    @Test
    void relay_failureInTheMiddleOfTheBatch_marksOnlyThePrefixAndStops() {

        when(outboxRepository.advisoryLockAcquired(anyLong())).thenReturn(true);
        when(outboxRepository.findByPublishedAtIsNullAndParkedFalseOrderBySeqAsc(any(Pageable.class)))
                .thenReturn(List.of(event(1L), event(2L), event(3L), event(4L), event(5L)));
        when(kafkaTemplate.send(anyRecord())).thenReturn(acked(), acked(), timedOut(), acked(), acked());

        outboxRelay.relay();

        InOrder inOrder = inOrder(outboxRepository);
        inOrder.verify(outboxRepository).markPublished(1L);
        inOrder.verify(outboxRepository).markPublished(2L);
        inOrder.verify(outboxRepository).recordError(contains("TimeoutException"), eq(3L));
        verify(outboxRepository, never()).markPublished(3L);
        verify(outboxRepository, never()).markPublished(4L);
        verify(outboxRepository, never()).markPublished(5L);

    }

    private static OutboxEvent event(long seq) {

        return OutboxEvent.builder()
                .seq(seq)
                .id(UUID.randomUUID())
                .aggregateType(UserEvents.AGGREGATE_TYPE)
                .aggregateId(UUID.randomUUID().toString())
                .eventType(UserEventType.USER_REGISTERED.value())
                .topic(UserEvents.TOPIC)
                .payload("{}")
                .build();

    }

    private static CompletableFuture<SendResult<String, String>> acked() {

        return CompletableFuture.completedFuture(null);

    }

    private static CompletableFuture<SendResult<String, String>> timedOut() {

        return CompletableFuture.failedFuture(new TimeoutException("Expiring 1 record(s) for user.events.v1-0"));

    }

    private static ProducerRecord<String, String> anyRecord() {

        return any();

    }
}
