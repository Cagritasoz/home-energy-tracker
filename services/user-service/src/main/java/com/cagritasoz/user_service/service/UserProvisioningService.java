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
    }

    // TODO: Handle a user's email changing case.
    private User provision(UUID sub, Jwt token) {

        String email = token.getClaimAsString("email");

        // Defensive checks, in theory these checks should never throw.
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
        userRepository.insertIgnoringConflict(sub, email, resolveDisplayName(token));

        // The request with the losing transaction still gets the inserted user via fallback query. No deliberate 409.
        // Only true under READ COMMITTED which is the default.
        return userRepository.findById(sub)
                .orElseThrow(() -> new IllegalStateException("User " + sub + " missing after provisioning."));
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
