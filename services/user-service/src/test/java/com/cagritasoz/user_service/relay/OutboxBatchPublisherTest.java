package com.cagritasoz.user_service.relay;

import com.cagritasoz.contracts.EventHeaders;
import com.cagritasoz.contracts.user.UserEventType;
import com.cagritasoz.contracts.user.UserEvents;
import com.cagritasoz.user_service.entity.OutboxEvent;
import com.cagritasoz.user_service.repository.OutboxRepository;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.errors.RecordTooLargeException;
import org.apache.kafka.common.errors.TimeoutException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.domain.Pageable;
import org.springframework.kafka.KafkaException;
import org.springframework.kafka.core.KafkaProducerException;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

// The publisher's decisions with the repository and the Kafka producer mocked: what is sent, what is
// marked, what is recorded as an error, and what the relay is told. A Kafka future is made to "ack" or
// "fail" by returning a completed or failed CompletableFuture. Real timing, real brokers and the lock
// between two instances are RelayIT's job.
@ExtendWith(MockitoExtension.class)
class OutboxBatchPublisherTest {

    @Mock
    private OutboxRepository outboxRepository;

    @Mock
    private KafkaTemplate<String, String> kafkaTemplate;

    @Captor
    private ArgumentCaptor<ProducerRecord<String, String>> recordCaptor;

    private OutboxBatchPublisher outboxBatchPublisher;

    @BeforeEach
    void setUp() {

        outboxBatchPublisher = new OutboxBatchPublisher(outboxRepository, kafkaTemplate, 50, 5_000);

    }

    // ---- nothing to do -----------------------------------------------------------------------------

    @Test
    void publishBatch_lockHeldByAnotherInstance_returnsIdleAndTouchesNothing() {

        when(outboxRepository.advisoryLockAcquired(anyLong())).thenReturn(false);

        BatchResult result = outboxBatchPublisher.publishBatch();

        assertThat(result.outcome()).isEqualTo(BatchResult.Outcome.IDLE);
        verify(outboxRepository).advisoryLockAcquired(anyLong());
        verifyNoMoreInteractions(outboxRepository); // did not even read the rows
        verifyNoInteractions(kafkaTemplate); // no send called

    }

    @Test
    void publishBatch_noPendingRows_returnsIdleWithoutSending() {

        lockAcquiredAndPending(); // No pending rows.

        BatchResult result = outboxBatchPublisher.publishBatch();

        assertThat(result.outcome()).isEqualTo(BatchResult.Outcome.IDLE);
        assertThat(result.published()).isZero();
        verifyNoInteractions(kafkaTemplate);
        verify(outboxRepository, never()).markPublished(anyList());

    }

    // ---- success -----------------------------------------------------------------------------------

    @Test
    void publishBatch_everyRowAcked_marksAllInOneStatementAndReportsPublished() {

        lockAcquiredAndPending(event(1L), event(2L), event(3L));
        when(kafkaTemplate.send(anyRecord())).thenReturn(acked(), acked(), acked());

        BatchResult result = outboxBatchPublisher.publishBatch();

        assertThat(result.outcome()).isEqualTo(BatchResult.Outcome.PUBLISHED);
        assertThat(result.published()).isEqualTo(3);
        assertThat(result.full()).isFalse(); // 3 rows < batch size 50: the backlog is drained
        assertThat(result.failure()).isNull();
        verify(outboxRepository).markPublished(List.of(1L, 2L, 3L));
        verify(outboxRepository, never()).recordError(anyString(), anyLong());

    }

    @Test
    void publishBatch_batchFilledToItsSize_reportsFullSoTheRelayKeepsDraining() {

        OutboxBatchPublisher smallBatches = new OutboxBatchPublisher(outboxRepository, kafkaTemplate, 3, 5_000);
        lockAcquiredAndPending(event(1L), event(2L), event(3L));
        when(kafkaTemplate.send(anyRecord())).thenReturn(acked(), acked(), acked());

        BatchResult result = smallBatches.publishBatch();

        assertThat(result.outcome()).isEqualTo(BatchResult.Outcome.PUBLISHED);
        assertThat(result.full()).isTrue();

    }

    @Test
    void publishBatch_deadlineAlreadyPassed_stillCountsRowsThatWereAcked() {

        // A zero timeout means the deadline has passed before the first get(). A future that is already
        // complete must still be accepted: the deadline limits waiting, not reading a finished result.
        OutboxBatchPublisher noWaiting = new OutboxBatchPublisher(outboxRepository, kafkaTemplate, 50, 0);
        lockAcquiredAndPending(event(1L), event(2L));
        when(kafkaTemplate.send(anyRecord())).thenReturn(acked(), acked());

        BatchResult result = noWaiting.publishBatch();

        assertThat(result.outcome()).isEqualTo(BatchResult.Outcome.PUBLISHED);
        verify(outboxRepository).markPublished(List.of(1L, 2L));

    }

    // ---- what is sent ------------------------------------------------------------------------------

    @Test
    void publishBatch_record_carriesKeyTopicPayloadAndAllHeaders() {

        UUID eventId = UUID.randomUUID();
        UUID correlationId = UUID.randomUUID();
        OutboxEvent event = OutboxEvent.builder()
                .seq(1L)
                .id(eventId)
                .aggregateType(UserEvents.AGGREGATE_TYPE)
                .aggregateId("11111111-1111-1111-1111-111111111111")
                .eventType(UserEventType.USER_UPDATED.value())
                .schemaVersion(2)
                .topic(UserEvents.TOPIC)
                .payload("{\"eventType\":\"UserUpdated\"}")
                .correlationId(correlationId)
                .build();
        lockAcquiredAndPending(event);
        when(kafkaTemplate.send(anyRecord())).thenReturn(acked());

        outboxBatchPublisher.publishBatch();

        verify(kafkaTemplate).send(recordCaptor.capture());
        ProducerRecord<String, String> record = recordCaptor.getValue();
        assertThat(record.topic()).isEqualTo(UserEvents.TOPIC);
        assertThat(record.key()).isEqualTo("11111111-1111-1111-1111-111111111111"); // aggregate id, as text
        assertThat(record.value()).isEqualTo("{\"eventType\":\"UserUpdated\"}");   // payload verbatim
        assertThat(header(record, EventHeaders.EVENT_ID)).isEqualTo(eventId.toString());
        assertThat(header(record, EventHeaders.EVENT_TYPE)).isEqualTo("UserUpdated");
        assertThat(header(record, EventHeaders.SCHEMA_VERSION)).isEqualTo("2");
        assertThat(header(record, EventHeaders.CORRELATION_ID)).isEqualTo(correlationId.toString());

    }

    @Test
    void publishBatch_rowWithoutCorrelationId_sendsNoCorrelationHeader() {

        lockAcquiredAndPending(event(1L)); // the helper sets no correlation id
        when(kafkaTemplate.send(anyRecord())).thenReturn(acked());

        outboxBatchPublisher.publishBatch();

        verify(kafkaTemplate).send(recordCaptor.capture());
        assertThat(recordCaptor.getValue().headers().lastHeader(EventHeaders.CORRELATION_ID)).isNull();
        assertThat(recordCaptor.getValue().headers().lastHeader(EventHeaders.EVENT_ID)).isNotNull();

    }

    @Test
    void publishBatch_rows_areHandedToTheProducerInSeqOrder() {

        lockAcquiredAndPending(event(1L), event(2L), event(3L));
        when(kafkaTemplate.send(anyRecord())).thenReturn(acked(), acked(), acked());

        outboxBatchPublisher.publishBatch();

        verify(kafkaTemplate, times(3)).send(recordCaptor.capture());
        // The repository returns the rows oldest-first; the publisher must keep that order (the producer
        // appends records of one partition in send order).
        assertThat(recordCaptor.getAllValues()).extracting(r -> header(r, EventHeaders.EVENT_ID))
                .containsExactly(ids.get(1L).toString(), ids.get(2L).toString(), ids.get(3L).toString());

    }

    // ---- the producer reports a failure ------------------------------------------------------------

    @Test
    void relay_failureInTheMiddleOfTheBatch_marksOnlyThePrefixAndStops() {

        lockAcquiredAndPending(event(1L), event(2L), event(3L), event(4L), event(5L));
        when(kafkaTemplate.send(anyRecord())).thenReturn(acked(), acked(), timedOut(), acked(), acked());

        BatchResult result = outboxBatchPublisher.publishBatch();

        InOrder inOrder = inOrder(outboxRepository);
        inOrder.verify(outboxRepository).recordError(contains("TimeoutException"), eq(3L));
        inOrder.verify(outboxRepository).markPublished(List.of(1L, 2L));
        verify(outboxRepository, never()).markPublished(List.of(1L, 2L, 3L, 4L, 5L));
        // Rows 4 and 5 were handed to the producer in phase 1 (that is the pipelining) but stay unmarked.
        verify(kafkaTemplate, times(5)).send(anyRecord());
        assertThat(result.outcome()).isEqualTo(BatchResult.Outcome.FAILED);
        assertThat(result.published()).isEqualTo(2);
        assertThat(result.failure()).isInstanceOf(TimeoutException.class);

    }

    @Test
    void publishBatch_firstRowFails_marksNothing() {

        lockAcquiredAndPending(event(1L), event(2L), event(3L));
        when(kafkaTemplate.send(anyRecord())).thenReturn(timedOut(), acked(), acked());

        BatchResult result = outboxBatchPublisher.publishBatch();

        verify(outboxRepository).recordError(contains("TimeoutException"), eq(1L));
        verify(outboxRepository, never()).markPublished(anyList());
        assertThat(result.outcome()).isEqualTo(BatchResult.Outcome.FAILED);
        assertThat(result.published()).isZero();

    }

    @Test
    void publishBatch_failureCause_isSpringsWrapperStrippedButKafkasOwnCauseKept() {

        // The Kafka exception has a cause of its own. Only spring-kafka's wrapper may be stripped; the
        // Kafka exception is the real reason and must reach last_error, the log and BatchResult.failure as is.
        TimeoutException kafkaException = new TimeoutException("Expiring 1 record(s)", new RuntimeException("socket closed"));
        lockAcquiredAndPending(event(1L));
        when(kafkaTemplate.send(anyRecord())).thenReturn(CompletableFuture.failedFuture(
                new KafkaProducerException(new ProducerRecord<>(UserEvents.TOPIC, "k", "v"), "Failed to send", kafkaException)));

        BatchResult result = outboxBatchPublisher.publishBatch();

        assertThat(result.failure()).isSameAs(kafkaException);
        verify(outboxRepository).recordError(kafkaException.toString(), 1L);

    }

    @Test
    void publishBatch_failureWithoutSpringWrapper_isRecordedAsIs() {

        RecordTooLargeException tooLarge = new RecordTooLargeException("The message is 2000000 bytes");
        lockAcquiredAndPending(event(1L));
        when(kafkaTemplate.send(anyRecord())).thenReturn(CompletableFuture.failedFuture(tooLarge));

        BatchResult result = outboxBatchPublisher.publishBatch();

        assertThat(result.failure()).isSameAs(tooLarge);

    }

    @Test
    void publishBatch_ackNeverArrives_batchDeadlinePassesAndRowIsRecordedAsTimedOut() {

        OutboxBatchPublisher impatient = new OutboxBatchPublisher(outboxRepository, kafkaTemplate, 50, 50);
        lockAcquiredAndPending(event(1L), event(2L), event(3L));
        // Row 2's ack never comes (a future nobody completes); row 3 is acked but sits behind it.
        when(kafkaTemplate.send(anyRecord())).thenReturn(acked(), new CompletableFuture<>(), acked());

        BatchResult result = impatient.publishBatch();

        // This is the relay's own deadline, java.util.concurrent.TimeoutException - not Kafka's.
        assertThat(result.failure()).isInstanceOf(java.util.concurrent.TimeoutException.class);
        verify(outboxRepository).recordError(contains("java.util.concurrent.TimeoutException"), eq(2L));
        verify(outboxRepository).markPublished(List.of(1L));
        assertThat(result.outcome()).isEqualTo(BatchResult.Outcome.FAILED);

    }

    // ---- send() itself throws ----------------------------------------------------------------------

    @Test
    void relay_sendThrows_recordsTheRealCauseNotSpringsWrapperAndSendsNothingAfterIt() {

        lockAcquiredAndPending(event(1L), event(2L), event(3L));
        // What KafkaTemplate throws when the producer has no topic metadata (checked against spring-kafka 4.1.1).
        when(kafkaTemplate.send(anyRecord())).thenReturn(acked()).thenThrow( // ack the first, throw processing the second.
                new KafkaException("Send failed", new TimeoutException("Topic user.events.v1 not present in metadata after 5000 ms.")));

        BatchResult result = outboxBatchPublisher.publishBatch();

        verify(outboxRepository).recordError(contains("not present in metadata"), eq(2L));
        verify(outboxRepository).markPublished(List.of(1L));
        verify(kafkaTemplate, times(2)).send(anyRecord()); // row 3 was never handed to the producer
        assertThat(result.outcome()).isEqualTo(BatchResult.Outcome.FAILED);
        assertThat(result.failure()).isInstanceOf(TimeoutException.class);

    }

    @Test
    void publishBatch_firstSendThrows_marksNothingAndSendsNothingMore() {

        lockAcquiredAndPending(event(1L), event(2L));
        when(kafkaTemplate.send(anyRecord())).thenThrow(new KafkaException("Send failed", new TimeoutException("no metadata")));

        BatchResult result = outboxBatchPublisher.publishBatch();

        verify(kafkaTemplate, times(1)).send(anyRecord());
        verify(outboxRepository).recordError(contains("no metadata"), eq(1L));
        verify(outboxRepository, never()).markPublished(anyList());
        assertThat(result.published()).isZero();

    }

    @Test
    void publishBatch_rowWithoutTopic_failsThatRowOnlyAndSendsNothingAfterIt() {

        // ProducerRecord rejects a null topic with IllegalArgumentException before the producer is involved.
        // That is a local fault of this row: it must be recorded like any send failure, not escape and roll
        // back the marks of the rows before it.
        lockAcquiredAndPending(event(1L), event(2L, null), event(3L));
        when(kafkaTemplate.send(anyRecord())).thenReturn(acked());

        BatchResult result = outboxBatchPublisher.publishBatch();

        verify(kafkaTemplate, times(1)).send(anyRecord());
        verify(outboxRepository).recordError(contains("IllegalArgumentException"), eq(2L));
        verify(outboxRepository).markPublished(List.of(1L));
        assertThat(result.outcome()).isEqualTo(BatchResult.Outcome.FAILED);

    }

    // ---- shutdown and database errors --------------------------------------------------------------

    @Test
    void publishBatch_interruptedWhileWaiting_isNotAFailureOfTheRow() {

        lockAcquiredAndPending(event(1L));
        when(kafkaTemplate.send(anyRecord())).thenReturn(new CompletableFuture<>());
        Thread.currentThread().interrupt(); // what shutdown does to the scheduler thread

        try {
            BatchResult result = outboxBatchPublisher.publishBatch();

            assertThat(result.outcome()).isEqualTo(BatchResult.Outcome.INTERRUPTED);
            assertThat(Thread.currentThread().isInterrupted()).as("interrupt flag is kept").isTrue();
            verify(outboxRepository, never()).recordError(anyString(), anyLong()); // no attempt is counted
            verify(outboxRepository, never()).markPublished(anyList());
        }
        finally {
            Thread.interrupted(); // clear the flag so it cannot leak into the next test on this thread
        }

    }

    @Test
    void publishBatch_markingFails_theDatabaseErrorPropagatesSoTheTransactionRollsBack() {

        lockAcquiredAndPending(event(1L));
        when(kafkaTemplate.send(anyRecord())).thenReturn(acked());
        when(outboxRepository.markPublished(anyList())).thenThrow(new DataAccessResourceFailureException("connection lost"));

        assertThatThrownBy(() -> outboxBatchPublisher.publishBatch())
                .isInstanceOf(DataAccessResourceFailureException.class);

    }

    // ---- helpers -----------------------------------------------------------------------------------

    // The event id of each row built by event(), by seq, so a test can tell the sent records apart.
    private final Map<Long, UUID> ids = new HashMap<>();

    private void lockAcquiredAndPending(OutboxEvent... events) {

        when(outboxRepository.advisoryLockAcquired(anyLong())).thenReturn(true);
        when(outboxRepository.findByPublishedAtIsNullAndParkedFalseOrderBySeqAsc(any(Pageable.class)))
                .thenReturn(List.of(events));

    }

    private OutboxEvent event(long seq) {

        return event(seq, UserEvents.TOPIC);

    }

    private OutboxEvent event(long seq, String topic) {

        UUID id = UUID.randomUUID();
        ids.put(seq, id);

        return OutboxEvent.builder()
                .seq(seq)
                .id(id)
                .aggregateType(UserEvents.AGGREGATE_TYPE)
                .aggregateId(UUID.randomUUID().toString())
                .eventType(UserEventType.USER_REGISTERED.value())
                .topic(topic)
                .payload("{}")
                .build();

    }

    private static String header(ProducerRecord<String, String> record, String name) {

        return new String(record.headers().lastHeader(name).value(), StandardCharsets.UTF_8);

    }

    // Future completed without exception.
    private static CompletableFuture<SendResult<String, String>> acked() {

        return CompletableFuture.completedFuture(null);

    }

    private static CompletableFuture<SendResult<String, String>> timedOut() {

        // KafkaTemplate never hands out the producer's exception bare: it completes the future with
        // KafkaProducerException("Failed to send") wrapping it (checked against spring-kafka 4.1.1).
        return CompletableFuture.failedFuture(new KafkaProducerException(
                new ProducerRecord<>(UserEvents.TOPIC, "k", "v"), "Failed to send",
                new TimeoutException("Expiring 1 record(s) for user.events.v1-0")));

    }

    private static ProducerRecord<String, String> anyRecord() {

        return any();

    }
}
