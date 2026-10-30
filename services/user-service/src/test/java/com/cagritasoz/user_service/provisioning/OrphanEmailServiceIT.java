package com.cagritasoz.user_service.provisioning;

import com.cagritasoz.user_service.deletion.DeletionPromotionService;
import com.cagritasoz.user_service.deletion.DeletionSchedulingService;
import com.cagritasoz.user_service.keycloak.KeycloakUnavailableException;
import com.cagritasoz.user_service.outbox.OutboxService;
import com.cagritasoz.user_service.testsupport.containers.PostgresTestContainerConfig;
import com.cagritasoz.user_service.testsupport.data.UserFixtures;
import com.cagritasoz.user_service.testsupport.keycloak.ScriptedKeycloakAdmin;
import com.cagritasoz.user_service.user.UserRepository;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// OrphanEmailService with the real repository, the real scheduling service and outbox, and a real Postgres; only
// Keycloak is scripted. The point is the chain it starts: after an orphan is released, the promoter really does free
// its address, and the new registration's insert then really does succeed. Every test runs in a rolled-back
// transaction (which also satisfies the MANDATORY transaction OutboxService needs); within one transaction
// CURRENT_TIMESTAMP does not move, so "the deadline is now" is also "due" for the promoter that follows.
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({PostgresTestContainerConfig.class, OutboxService.class, DeletionSchedulingService.class, DeletionPromotionService.class,
        OrphanEmailService.class, OrphanEmailServiceIT.Fakes.class})
@TestPropertySource(properties = {
        "app.user.deletion.grace=5m",
        "app.user.deletion.required-participants="
})
class OrphanEmailServiceIT {

    private static final String EMAIL = UserFixtures.ARTHUR_MORGAN_EMAIL;

    private static final String FAR_FUTURE = "2999-01-01T00:00:00Z";

    @TestConfiguration
    static class Fakes {

        @Bean
        ScriptedKeycloakAdmin keycloakAdmin() { // implements KeycloakAdmin interface.

            return new ScriptedKeycloakAdmin();

        }

        @Bean
        MeterRegistry meterRegistry() {

            return new SimpleMeterRegistry();

        }
    }

    @Autowired
    private OrphanEmailService service;

    @Autowired
    private DeletionPromotionService promotionService;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private ScriptedKeycloakAdmin keycloak;

    @Autowired
    private JdbcTemplate jdbc;

    @AfterEach
    void forgetTheScript() {

        keycloak.reset();

    }

    private UUID insertActiveHolder(String email) {

        UUID id = UUID.randomUUID();

        jdbc.update("INSERT INTO users (id, email, display_name) VALUES (?, ?, ?)", id, email, UserFixtures.ARTHUR_MORGAN_NAME);

        return id;

    }

    private UUID insertScheduledHolder(String email, String scheduledFor) {

        UUID id = UUID.randomUUID();

        jdbc.update("""
                INSERT INTO users (id, email, display_name, status, deletion_requested_at, deletion_scheduled_for)
                VALUES (?, ?, ?, 'DELETION_SCHEDULED', CURRENT_TIMESTAMP - interval '1 hour', ?::timestamptz)
                """, id, email, UserFixtures.ARTHUR_MORGAN_NAME, scheduledFor);

        return id;

    }

    private String statusOf(UUID id) {

        return jdbc.queryForObject("SELECT status FROM users WHERE id = ?", String.class, id);

    }

    private List<String> eventTypes() {

        return jdbc.queryForList("SELECT event_type FROM outbox_events ORDER BY seq", String.class);

    }

    // The whole chain: no Keycloak user -> scheduled with the deadline now and announced -> the promoter scrubs the
    // address -> the new registration's insert succeeds.
    @Test
    void releaseIfOrphaned_activeHolderWithoutKeycloakUser_endsWithTheAddressFreeForTheNewAccount() {

        UUID oldId = insertActiveHolder(EMAIL);
        UUID newId = UUID.randomUUID();

        assertThat(userRepository.insertIgnoringConflict(newId, EMAIL, "New")).as("the address is taken").isZero();

        assertThat(service.releaseIfOrphaned(EMAIL)).isEqualTo(OrphanEmailService.Outcome.ORPHAN_RELEASING);

        assertThat(statusOf(oldId)).isEqualTo("DELETION_SCHEDULED"); // Deletion is scheduled immediately.
        assertThat(eventTypes()).containsExactly("UserDeletionScheduled");
        assertThat(userRepository.findIdsDueForPromotion(10)).containsExactly(oldId);

        assertThat(promotionService.promote(oldId)).isTrue();

        assertThat(statusOf(oldId)).isEqualTo("DELETING");
        assertThat(userRepository.insertIgnoringConflict(newId, EMAIL, "New")).as("the address is free now").isOne();
        // No UserRegistered event since provisioning method is not called, only userRepo is called.
        assertThat(eventTypes()).containsExactly("UserDeletionScheduled", "UserDeletionStarted");

    }

    @Test
    void releaseIfOrphaned_holderStoredInAnotherCase_isStillTheHolder() {

        UUID oldId = insertActiveHolder("Arthur.Morgan@Example.com");

        assertThat(service.releaseIfOrphaned("arthur.morgan@example.com")).isEqualTo(OrphanEmailService.Outcome.ORPHAN_RELEASING);

        assertThat(statusOf(oldId)).isEqualTo("DELETION_SCHEDULED");
        assertThat(keycloak.lookupCalls()).containsExactly(oldId);

    }

    // An account that its owner scheduled for deletion weeks ahead, whose Keycloak user is gone: waiting out the grace
    // period would block the address for weeks, so the deadline moves to now. The schedule was announced already.
    @Test
    void releaseIfOrphaned_scheduledHolderWithoutKeycloakUser_isBroughtForwardWithoutANewEvent() {

        UUID oldId = insertScheduledHolder(EMAIL, FAR_FUTURE);

        assertThat(service.releaseIfOrphaned(EMAIL)).isEqualTo(OrphanEmailService.Outcome.ORPHAN_RELEASING);

        assertThat(statusOf(oldId)).isEqualTo("DELETION_SCHEDULED");
        assertThat(userRepository.findIdsDueForPromotion(10)).containsExactly(oldId);
        assertThat(eventTypes()).isEmpty();
        assertThat(promotionService.promote(oldId)).isTrue();

    }

    // Two registrations hit the same orphan: the second finds it already scheduled and due, and announces nothing again.
    @Test
    void releaseIfOrphaned_calledTwiceForTheSameOrphan_announcesItOnce() {

        UUID oldId = insertActiveHolder(EMAIL);

        service.releaseIfOrphaned(EMAIL);
        OrphanEmailService.Outcome second = service.releaseIfOrphaned(EMAIL);

        assertThat(second).isEqualTo(OrphanEmailService.Outcome.ORPHAN_RELEASING);
        assertThat(eventTypes()).containsExactly("UserDeletionScheduled");
        assertThat(statusOf(oldId)).isEqualTo("DELETION_SCHEDULED");

    }

    @Test
    void releaseIfOrphaned_holderStillInKeycloak_changesNothing() {

        UUID oldId = insertActiveHolder(EMAIL);
        keycloak.hasUser(oldId);

        assertThat(service.releaseIfOrphaned(EMAIL)).isEqualTo(OrphanEmailService.Outcome.HELD_BY_LIVE_ACCOUNT);

        assertThat(statusOf(oldId)).isEqualTo("ACTIVE");
        assertThat(eventTypes()).isEmpty();

    }

    // A Keycloak that cannot answer must never turn every colliding account into an "orphan".
    @Test
    void releaseIfOrphaned_keycloakCannotBeAsked_changesNothing() {

        UUID oldId = insertActiveHolder(EMAIL);
        keycloak.failingWith(new KeycloakUnavailableException("down", true));

        assertThatThrownBy(() -> service.releaseIfOrphaned(EMAIL)).isInstanceOf(KeycloakUnavailableException.class);

        assertThat(statusOf(oldId)).isEqualTo("ACTIVE");
        assertThat(eventTypes()).isEmpty();

    }

    @Test
    void releaseIfOrphaned_nobodyHoldsTheAddress_isNoHolderAndNeverAsksKeycloak() {

        assertThat(service.releaseIfOrphaned(EMAIL)).isEqualTo(OrphanEmailService.Outcome.NO_HOLDER);

        assertThat(keycloak.lookupCalls()).isEmpty();

    }
}
