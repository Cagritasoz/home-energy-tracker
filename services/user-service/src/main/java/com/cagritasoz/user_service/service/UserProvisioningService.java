package com.cagritasoz.user_service.service;

import com.cagritasoz.user_service.aspect.SkipLogging;
import com.cagritasoz.user_service.entity.User;
import com.cagritasoz.user_service.exception.AccountNotActiveException;
import com.cagritasoz.user_service.exception.EmailNotVerifiedException;
import com.cagritasoz.user_service.exception.MissingIdentityClaimException;
import com.cagritasoz.user_service.model.UserStatus;
import com.cagritasoz.user_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Objects;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserProvisioningService {

    private final UserRepository userRepository;

    private final OutboxService outboxService;

    @Transactional
    @SkipLogging
    // TODO: Evaluate whether it is worth it for other services to use the found/provisioned user without firing a separate "findById" query.
    public void ensureUsable(Jwt token) {

        UUID sub = UUID.fromString(Objects.requireNonNull(token.getSubject()));

        User user = userRepository.findById(sub)
                .orElseGet(() -> provision(sub, token)); // If user does not exist, insert user.

        if(user.getStatus() != UserStatus.ACTIVE) { // Reject tokens that have outlived the accounts' usability.

            throw new AccountNotActiveException();

        }

        syncEmailIfChanged(user, token);
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
        User user = userRepository.findById(sub)
                .orElseThrow(() -> new IllegalStateException("User " + sub + " missing after provisioning."));

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

    // Runs on every request, not just provisioning: a user can change their email in Keycloak long
    // after their account row was first created, and nothing else ever looks at it again
    // otherwise. The common case (no change) only compares against the already-loaded entity - no
    // extra query. Only when the email really changed does it write, re-read the row and record a
    // UserUpdated event. Requiring email_verified here too stops an in-progress Keycloak email change
    // (old address still active until the new one is confirmed) from overwriting the row early.
    private void syncEmailIfChanged(User user, Jwt token) {

        String email = token.getClaimAsString("email");

        if (email == null || email.isBlank() || email.equals(user.getEmail())) {

            return;

        }

        if (!Boolean.TRUE.equals(token.getClaimAsBoolean("email_verified"))) {

            return;

        }

        // syncEmail returns the rows changed: 1 = this request changed the email, 0 = nothing to do
        // (a concurrent request already synced it, or the account is no longer ACTIVE) - and then
        // there is no event either.
        //
        // It is a native UPDATE, so it bypasses Hibernate: the User that ensureUsable() loaded stays in
        // the persistence context with the OLD email, version and updated_at, and a plain findById would
        // just hand that stale object back. "clearAutomatically = true" on syncEmail() empties the
        // context after the update, so the findById below really queries the table.
        int updated = userRepository.syncEmail(user.getId(), email);

        if(updated == 0) {
            return;
        }

        // The event needs the row as it is NOW: the new version, the trigger-stamped updated_at, and the
        // current display name and timezone, which a concurrent request may have changed since this
        // request loaded the user.
        User syncedUser = userRepository.findById(user.getId())
                .orElseThrow(() -> new IllegalStateException("User " + user.getId() + " missing after syncing email."));

        outboxService.recordUserUpdated(syncedUser);

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
