package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.user.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Objects;
import java.util.UUID;

// The customer's side of the deletion saga: ask for the account to be deleted, and change one's mind during the
// grace period. Shares its base path with UserProfileController (the two controllers split the /me routes by
// feature, not by URL).
@RestController
@RequestMapping("/api/v1/users/me")
@PreAuthorize("hasRole('user')")
@RequiredArgsConstructor
public class AccountDeletionController {

    private final DeletionSchedulingService deletionSchedulingService;

    // Does not delete anything: it starts the grace period. 202 with the account as it now stands, so the frontend
    // can show the deletion date straight away; a repeat while scheduled answers the same date.
    @DeleteMapping
    public ResponseEntity<UserResponse> deleteMe(@AuthenticationPrincipal Jwt token) {

        UserResponse user = deletionSchedulingService.scheduleDeletion(currentUserId(token));

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(user);
    }

    @PostMapping("/deletion/cancel")
    public ResponseEntity<Void> cancelDeletion(@AuthenticationPrincipal Jwt token) {

        deletionSchedulingService.cancelDeletion(currentUserId(token));

        return ResponseEntity.noContent().build();
    }

    private UUID currentUserId(Jwt token) {
        return UUID.fromString(Objects.requireNonNull(token.getSubject())); // Fail loudly if null. Should not happen.
    }
}
