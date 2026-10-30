package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.keycloak.KeycloakUnavailableException;
import com.cagritasoz.user_service.testsupport.containers.PostgresTestContainerConfig;
import com.cagritasoz.user_service.testsupport.keycloak.ScriptedKeycloakAdmin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// KeycloakDeletionService over a real Postgres, with Keycloak replaced by a ScriptedKeycloakAdmin. What it proves is
// what a mocked repository cannot: that the Keycloak call really happens with no transaction open (the whole point of
// the service), and that the stamp behaves as one compare-and-set under real concurrency.
//
// The tests commit for real (no rolled-back test transaction, which would itself be an open transaction around the
// Keycloak call), so each one cleans the tables before and after.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({PostgresTestContainerConfig.class, KeycloakDeletionService.class, KeycloakDeletionIT.FakeKeycloak.class})
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class KeycloakDeletionIT {

    private static final UUID ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @TestConfiguration
    static class FakeKeycloak {

        @Bean
        ScriptedKeycloakAdmin keycloakAdmin() {

            return new ScriptedKeycloakAdmin();

        }
    }

    @Autowired
    private KeycloakDeletionService service;

    @Autowired
    private ScriptedKeycloakAdmin keycloak;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void cleanTables() {

        clean();

    }

    @AfterEach
    void cleanTablesAndForgetTheScript() {

        clean();
        keycloak.reset();

    }

    private void clean() {

        jdbc.execute("TRUNCATE TABLE outbox_events, user_deletion_participants, users RESTART IDENTITY CASCADE");

    }
    // keycloak_deleted_at is null.
    private void insertDeletingUser(UUID id) {

        jdbc.update("""
                INSERT INTO users (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for, deletion_started_at)
                VALUES (?, ?, 'Deleted user', 'DELETING',
                        CURRENT_TIMESTAMP - interval '1 hour', CURRENT_TIMESTAMP - interval '1 hour', CURRENT_TIMESTAMP - interval '1 minute')
                """, id, "deleted+" + id + "@invalid");

    }

    private Instant keycloakDeletedAt(UUID id) {

        return jdbc.queryForObject("SELECT keycloak_deleted_at FROM users WHERE id = ?", Instant.class, id);

    }

    private Integer version(UUID id) {

        return jdbc.queryForObject("SELECT version FROM users WHERE id = ?", Integer.class, id);

    }

    // The reason this class is not @Transactional: a connection and, with an UPDATE, a row lock held for as long as
    // Keycloak takes. The fake checks the thread's transaction state at the moment of the call.
    @Test
    void deleteFromKeycloak_callsKeycloakWithNoTransactionOpen() {

        insertDeletingUser(ID);
        AtomicBoolean transactionOpenDuringTheCall = new AtomicBoolean(true);
        keycloak.hasUser(ID).onDelete(id -> transactionOpenDuringTheCall.set( // register consumer
                TransactionSynchronizationManager.isActualTransactionActive()));

        boolean stamped = service.deleteFromKeycloak(ID);

        assertThat(stamped).isTrue();
        assertThat(transactionOpenDuringTheCall).as("a transaction was open during the Keycloak call").isFalse(); // Assert that no transaction.
        assertThat(keycloak.deleteCalls()).containsExactly(ID);
        assertThat(keycloakDeletedAt(ID)).isNotNull();
        assertThat(version(ID)).isOne();

    }

    @Test
    void deleteFromKeycloak_keycloakFails_leavesTheRowUnstamped() {

        insertDeletingUser(ID);
        keycloak.hasUser(ID).failingWith(new KeycloakUnavailableException("Keycloak answered 503 to a user delete", true));

        assertThatThrownBy(() -> service.deleteFromKeycloak(ID)).isInstanceOf(KeycloakUnavailableException.class);

        assertThat(keycloakDeletedAt(ID)).isNull();
        assertThat(version(ID)).isZero();
        assertThat(service.findPending(10)).containsExactly(ID); // Still on the list for the next tick.

    }

    // The process died after Keycloak deleted the user and before the stamp: the next tick deletes again, Keycloak says
    // "not there" (a success for the fake, as for the real client), and the stamp is written.
    @Test
    void deleteFromKeycloak_afterACrashBetweenTheCallAndTheStamp_completesOnTheNextTick() {

        insertDeletingUser(ID);
        keycloak.hasUser(ID);
        keycloak.deleteUser(ID); // The first run got this far...
        assertThat(keycloakDeletedAt(ID)).isNull(); // ...and died before stamping.

        boolean stamped = service.deleteFromKeycloak(ID);

        assertThat(stamped).isTrue();
        assertThat(keycloakDeletedAt(ID)).isNotNull();
        assertThat(keycloak.deleteCalls()).hasSize(2);
        assertThat(service.findPending(10)).isEmpty();

    }

    @Test
    void deleteFromKeycloak_sameAccountAgain_stampsNothingTheSecondTime() {

        insertDeletingUser(ID);

        boolean first = service.deleteFromKeycloak(ID);
        Instant stamp = keycloakDeletedAt(ID);
        boolean second = service.deleteFromKeycloak(ID);

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(keycloakDeletedAt(ID)).isEqualTo(stamp);
        assertThat(version(ID)).isOne();

    }

    // Two instances working through the same list: with no row lock held during the call, both can be inside Keycloak at
    // the same time (the barrier proves they are), both delete (idempotent), and exactly one stamps.
    @Test
    void deleteFromKeycloak_twoInstancesAtOnce_bothCallKeycloakAndExactlyOneStamps() throws Exception {

        insertDeletingUser(ID);
        CyclicBarrier bothInsideKeycloak = new CyclicBarrier(2);
        keycloak.onDelete(id -> {

            try {

                bothInsideKeycloak.await(20, TimeUnit.SECONDS);

            }
            catch (Exception e) {

                throw new IllegalStateException("the two calls were not in Keycloak at the same time", e);

            }

        });

        ExecutorService threads = Executors.newFixedThreadPool(2);

        try {

            Future<Boolean> one = threads.submit(() -> service.deleteFromKeycloak(ID));
            Future<Boolean> two = threads.submit(() -> service.deleteFromKeycloak(ID));

            List<Boolean> results = List.of(one.get(30, TimeUnit.SECONDS), two.get(30, TimeUnit.SECONDS));

            assertThat(results).containsExactlyInAnyOrder(true, false);

        }
        finally {

            threads.shutdownNow();

        }

        assertThat(keycloak.deleteCalls()).hasSize(2);
        assertThat(version(ID)).isOne();

    }
}
