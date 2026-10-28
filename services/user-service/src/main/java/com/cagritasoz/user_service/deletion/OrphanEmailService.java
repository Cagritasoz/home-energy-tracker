package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.keycloak.KeycloakAdmin;
import com.cagritasoz.user_service.user.User;
import com.cagritasoz.user_service.user.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.Optional;

// Frees an email address that is held by an account nobody can log in to anymore.
//
// How it happens: an operator deletes a user in Keycloak's console. Our row stays ACTIVE (nothing told us), and it keeps
// the address in uq_users_email. When the same person registers again, Keycloak accepts it (the address is free there)
// and issues a new sub, but the new account cannot be provisioned: its address belongs to the old row.
//
// What this does about it, when provisioning asks (it only asks after the insert failed on the address):
//   1. find the account that holds the address;
//   2. ask Keycloak whether that account's sub still exists;
//   3. if it does not, schedule the old account for immediate deletion: the promoter scrubs its address within a tick
//      or two and the new registration can then be provisioned;
//      if it does, the address really is in use (Keycloak and our row disagree about whose it is): report that, and
//      change nothing.
//
// The address is never handed to the new account directly, because that would mean deciding in the middle of a request
// that one person's data may be replaced by another's; the old account goes through the normal deletion path (its
// events, its participants' cleanup) like any other.
//
// Not @Transactional, on purpose: step 2 is a call to Keycloak and must not run inside a transaction. The read in step
// 1 and the scheduling in step 3 are short transactions of their own.
@Slf4j
@Service
public class OrphanEmailService {

    public enum Outcome {

        // No live account holds the address (it was released between the failed insert and now): just try again.
        NO_HOLDER,

        // The holder has no Keycloak user; it is now scheduled for immediate deletion, and the address will be free soon.
        ORPHAN_RELEASING,

        // The holder exists in Keycloak: the address is genuinely taken. Nothing was changed.
        HELD_BY_LIVE_ACCOUNT

    }

    private final UserRepository userRepository;

    private final KeycloakAdmin keycloakAdmin;

    private final DeletionSchedulingService schedulingService;

    private final Counter releasedCounter;

    private final Counter liveCounter;

    public OrphanEmailService(UserRepository userRepository,
                              KeycloakAdmin keycloakAdmin,
                              DeletionSchedulingService schedulingService,
                              MeterRegistry meterRegistry) {

        this.userRepository = userRepository;
        this.keycloakAdmin = keycloakAdmin;
        this.schedulingService = schedulingService;
        this.releasedCounter = Counter.builder("user.email.orphan.released")
                .description("Accounts without a Keycloak user that were scheduled for immediate deletion to free their address")
                .register(meterRegistry);
        this.liveCounter = Counter.builder("user.email.collision.live")
                .description("Registrations refused because their address is held by an account that still exists in Keycloak")
                .register(meterRegistry);

    }

    // A KeycloakUnavailableException (Keycloak down, slow or the circuit open) propagates: without an answer nothing
    // can be decided, and the caller tells the client to try again later.
    public Outcome releaseIfOrphaned(String email) {

        Optional<User> holder = userRepository.findLiveByEmail(email);

        if (holder.isEmpty()) {

            return Outcome.NO_HOLDER;

        }

        User account = holder.get();

        if (keycloakAdmin.userExists(account.getId())) { // check sub in keycloak.

            liveCounter.increment();

            // Ids only, never the address: this is a divergence between Keycloak and our row that an operator has to
            // look at (the address was probably changed in Keycloak's console).
            log.error("Account {} holds the email address of a new registration but exists in Keycloak with another one", account.getId());

            return Outcome.HELD_BY_LIVE_ACCOUNT;

        }

        schedulingService.scheduleForImmediateDeletion(account.getId()); //

        releasedCounter.increment();
        log.warn("Account {} has no Keycloak user any more; scheduled it for immediate deletion to free its email address", account.getId());

        return Outcome.ORPHAN_RELEASING;

    }
}
