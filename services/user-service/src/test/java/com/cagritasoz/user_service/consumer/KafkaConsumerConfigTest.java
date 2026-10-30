package com.cagritasoz.user_service.consumer;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.kafka.listener.ListenerExecutionFailedException;
import org.springframework.util.backoff.BackOff;
import org.springframework.util.backoff.BackOffExecution;

import static org.assertj.core.api.Assertions.assertThat;

// The decisions inside the consumer's error handler that can be checked without a broker: which failures count as
// "the database is the problem" and what that pause policy looks like. What the whole handler does to a real record
// (poison to the dead-letter topic, a database failure retried and never dead-lettered) is proven against a real
// broker in ParticipantAckConsumerIT.
class KafkaConsumerConfigTest {

    @Test
    void causedByDatabase_dataAccessExceptionItself_isTrue() {

        assertThat(KafkaConsumerConfig.causedByDatabase(new DataAccessResourceFailureException("down"))).isTrue();

    }

    // Spring Kafka hands the handler the listener's exception wrapped, so the cause chain is what has to be searched.
    @Test
    void causedByDatabase_dataAccessExceptionInTheCauseChain_isTrue() {

        Exception wrapped = new ListenerExecutionFailedException("Listener failed",
                new IllegalStateException("tx failed", new DuplicateKeyException("dup")));

        assertThat(KafkaConsumerConfig.causedByDatabase(wrapped)).isTrue();

    }

    @Test
    void causedByDatabase_otherFailures_isFalse() {

        assertThat(KafkaConsumerConfig.causedByDatabase(new IllegalStateException("bug"))).isFalse();
        assertThat(KafkaConsumerConfig.causedByDatabase(new ListenerExecutionFailedException("x", new PoisonEventException("bad")))).isFalse();

    }

    // A cause that is its own cause must not loop forever.
    // see break keyword in the loop.
    @Test
    void causedByDatabase_selfReferencingCause_terminates() {

        Exception selfCaused = new Exception("loop") {
            @Override
            public synchronized Throwable getCause() {
                return this;
            }
        };

        assertThat(KafkaConsumerConfig.causedByDatabase(selfCaused)).isFalse();

    }

    // The point of the database policy: it never gives up, and it never waits longer than half a minute.
    @Test
    void databaseBackOff_growsToAMaximumAndNeverStops() {

        BackOff backOff = KafkaConsumerConfig.databaseBackOff();
        BackOffExecution execution = backOff.start();

        assertThat(execution.nextBackOff()).isEqualTo(1_000); // start with 1 sec, double by two, max is 30s
        assertThat(execution.nextBackOff()).isEqualTo(2_000);
        assertThat(execution.nextBackOff()).isEqualTo(4_000);

        long last = 0;

        for (int i = 0; i < 10_000; i++) {

            last = execution.nextBackOff();

            assertThat(last).as("attempt " + i).isNotEqualTo(BackOffExecution.STOP).isLessThanOrEqualTo(30_000);

        }

        assertThat(last).isEqualTo(30_000);

    }

    // Each failure gets its own countdown: one record's long retry must not shorten or lengthen another's.
    @Test
    void databaseBackOff_everyCallStartsFresh() {

        BackOffExecution first = KafkaConsumerConfig.databaseBackOff().start();
        first.nextBackOff();
        first.nextBackOff();

        assertThat(KafkaConsumerConfig.databaseBackOff().start().nextBackOff()).isEqualTo(1_000);

    }
}
