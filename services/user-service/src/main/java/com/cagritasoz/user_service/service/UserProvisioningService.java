package com.cagritasoz.user_service.service;

import com.cagritasoz.user_service.entity.User;
import com.cagritasoz.user_service.exception.AccountNotActiveException;
import com.cagritasoz.user_service.exception.EmailNotVerifiedException;
import com.cagritasoz.user_service.exception.MissingIdentityClaimException;
import com.cagritasoz.user_service.model.UserStatus;
import com.cagritasoz.user_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
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
    public void ensureUsable(Jwt token) {

        UUID sub = UUID.fromString(Objects.requireNonNull(token.getSubject()));

        User user = userRepository.findById(sub)
                .orElseGet(() -> insertFromClaims(sub, token)); // If user does not exist, insert user.

        if(user.getStatus() != UserStatus.ACTIVE) { // Reject tokens that have outlived the accounts' usability.

            throw new AccountNotActiveException();

        }
    }

    private User insertFromClaims(UUID sub, Jwt token) {

        String email = token.getClaimAsString("email");

        if(email == null || email.isBlank()) {

            throw new MissingIdentityClaimException("email");

        }

        if(!Boolean.TRUE.equals(token.getClaimAsBoolean("email_verified"))) {

            throw new EmailNotVerifiedException();

        }

        User user = User.builder()
                .id(sub)
                .email(email)
                .displayName(resolveDisplayName(token))
                .build();

        try {

            return userRepository.saveAndFlush(user);

        } catch (DataIntegrityViolationException e) {
            return userRepository.findById(sub)
                    .orElseThrow(() -> e);
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
