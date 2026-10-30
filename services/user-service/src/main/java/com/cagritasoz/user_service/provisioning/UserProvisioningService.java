package com.cagritasoz.user_service.provisioning;

import com.cagritasoz.user_service.outbox.OutboxService;
import com.cagritasoz.user_service.user.AccountDeletedException;
import com.cagritasoz.user_service.user.User;
import com.cagritasoz.user_service.user.UserRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionOperations;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

@Service
public class UserProvisioningService {

    private final UserRepository userRepository;

    private final OutboxService outboxService;

    private final OrphanEmailService orphanEmailService;

    // The database part of ensureUsable runs in a transaction opened here, not by an annotation on the method, because
    // the method as a whole must not be transactional: when the new account's address belongs to another account,
    // Keycloak is asked about that account, and no transaction may be open during a call to Keycloak.
    private final TransactionOperations transactions;

    // Counts requests whose verified token email differs from the stored one (see detectEmailMismatch). No tags:
    // the address itself must never end up in a metric.
    private final Counter emailMismatchCounter;

    public UserProvisioningService(UserRepository userRepository,
                                   OutboxService outboxService,
                                   OrphanEmailService orphanEmailService,
                                   TransactionOperations transactions,
                                   MeterRegistry meterRegistry) {

        this.userRepository = userRepository;
        this.outboxService = outboxService;
        this.orphanEmailService = orphanEmailService;
        this.transactions = transactions;
        this.emailMismatchCounter = Counter.builder("user.email.token.mismatch")
                .description("Requests whose verified token email differs from the email stored for the user")
                .register(meterRegistry);

    }

    // Not @Transactional itself (see the field above): the database work is one transaction inside, and the one case
    // that talks to Keycloak happens after that transaction has rolled back.
    public void ensureUsable(Jwt token) {

        try {

            transactions.executeWithoutResult(status -> ensureUsableInTransaction(token));

        }
        catch (EmailHeldByAnotherAccount e) {

            // The transaction has rolled back and has ended (nothing was written); the new account cannot be created while another
            // account holds its address. Find out whether that account is real, and tell the client what to do.
            resolveEmailCollision(token);

        }

    }

    // TODO: Evaluate whether it is worth it for other services to use the found/provisioned user without firing a separate "findById" query.
    private void ensureUsableInTransaction(Jwt token) {

        UUID sub = UUID.fromString(Objects.requireNonNull(token.getSubject()));

        User user = userRepository.findById(sub)
                .orElseGet(() -> provision(sub, token)); // If user does not exist, insert user.

        // Reject tokens that have outlived the account. A DELETION_SCHEDULED account is let through: it can still be
        // read and canceled, and the write rules are enforced where the row is changed.
        if(user.getStatus().isGone()) {

            throw new AccountDeletedException();

        }

        detectEmailMismatch(user, token);
    }

    private User provision(UUID sub, Jwt token) {

        String email = token.getClaimAsString("email");

        // Defensive checks, in theory these checks should never throw because of Keycloak handling them.
        if(email == null || email.isBlank()) {

            throw new MissingIdentityClaimException("email");

        }

        if(!Boolean.TRUE.equals(token.getClaimAsBoolean("email_verified"))) {

            throw new EmailNotVerifiedException();

        }

        // Handle racing requests, joins the same transaction as "ensureUsable()" method.
        // The losing transaction doesn't do "nothing" immediately.
        // If the winner hasn't committed yet, the loser waits on the primary key until
        // the winner commits or rolls back and only then does nothing.
        // If the winner rolls back, the loser's insert succeeds.
        // A native query runs as its own SQL statement the moment it is called - there is no Hibernate
        // write-behind queue to flush, unlike save(). The returned count is the rows inserted:
        // 1 = this request created the user, 0 = the id already existed (ON CONFLICT DO NOTHING).
        int inserted = userRepository.insertIgnoringConflict(sub, email, resolveDisplayName(token));

        // Same transaction and connection as the insert, so this SELECT sees the new row even though
        // it is not committed yet (a transaction always sees its own writes). The findById in
        // ensureUsable found nothing and Hibernate does not cache "not found", so this really
        // queries the table and returns the row with the database defaults (version 0, created_at).
        Optional<User> row = userRepository.findById(sub);

        if (row.isEmpty()) {

            // Nothing inserted and no row with this id: that means that the insert was refused by uq_users_email, so the address
            // belongs to a different account. (1 inserted row that cannot be read back would be a bug.)
            if (inserted == 0) {

                throw new EmailHeldByAnotherAccount();

            }

            throw new IllegalStateException("User " + sub + " missing after provisioning.");

        }

        User user = row.get();

        // The request with the losing transaction still gets the inserted user via fallback query. No deliberate 409.
        // Only true under READ COMMITTED which is the default.
        if(inserted == 0) {
            return user;
        }

        // Only the request that actually created the row records the event, so concurrent first
        // requests produce exactly one UserRegistered. It is written in this same transaction: if
        // anything after this fails, the user row and its event roll back together.
        outboxService.recordUserRegistered(user);

        return user;
    }

    // The address of a new registration belongs to another account. Keycloak accepted the registration, so as far as it is
    // concerned the address is free: the other account is either an orphan (its Keycloak user was deleted by hand), which
    // is released here, or a real account whose address differs between Keycloak and our row, which only an operator can
    // sort out. Either way this request cannot be served yet. A KeycloakUnavailableException from the lookup propagates
    // (503): without an answer nothing is decided.
    private void resolveEmailCollision(Jwt token) {

        switch (orphanEmailService.releaseIfOrphaned(token.getClaimAsString("email"))) {

            // The old account is on its way out (or was already gone): the address frees up within a promoter tick.
            case ORPHAN_RELEASING, NO_HOLDER -> throw new EmailBeingReleasedException();

            case HELD_BY_LIVE_ACCOUNT -> throw new EmailAlreadyInUseException();

        }

    }

    // Thrown inside the transaction to roll it back and leave it; never reaches a caller. No stack trace: it is control
    // flow, not an error.
    private static final class EmailHeldByAnotherAccount extends RuntimeException {

        EmailHeldByAnotherAccount() {

            super(null, null, false, false);

        }
    }

    // The token's email is read to create the row and never written into it afterwards. An email changes only through
    // user-service, which then knows about it, and a token is a copy of Keycloak's state up to 5 minutes old: syncing
    // it into the row would flip a freshly changed email back for as long as an old token is in use.
    //
    // What replaces the sync is detection. A verified token email that differs from the row is counted, so a real
    // divergence (an operator editing the address in Keycloak's console) shows up in a metric and is repaired by an
    // explicit admin action, not by every request. Expecting some noise for up to 5 minutes after a legitimate change.
    //
    // Case is ignored: Keycloak lower-cases every address while the row keeps what it was given, and
    // uq_users_email treats the two as the same address anyway. An unverified or missing claim says nothing about the
    // account, so it is not counted. The address is never logged and never a tag of the metric.
    private void detectEmailMismatch(User user, Jwt token) {

        String email = token.getClaimAsString("email");

        if (email == null || email.isBlank() || !Boolean.TRUE.equals(token.getClaimAsBoolean("email_verified"))) {

            return;

        }

        if (!email.equalsIgnoreCase(user.getEmail())) {

            emailMismatchCounter.increment();

        }

    }

    private String resolveDisplayName(Jwt token) {

        String name = token.getClaimAsString("name");
        if (name != null && !name.isBlank()) return name;

        String given = token.getClaimAsString("given_name");
        if (given != null && !given.isBlank()) return given;

        String username = token.getClaimAsString("preferred_username"); // username = email, see realm settings.
        if (username != null && !username.isBlank()) {
            int at = username.indexOf('@');
            return at > 0 ? username.substring(0, at) : username;
        }

        throw new MissingIdentityClaimException("display_name");

    }
}
