package com.cagritasoz.user_service.controller;

import com.cagritasoz.user_service.dto.UpdateUserRequest;
import com.cagritasoz.user_service.dto.UserResponse;
import com.cagritasoz.user_service.service.UserDeletionService;
import com.cagritasoz.user_service.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/me")
@PreAuthorize("hasRole('user')")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    private final UserDeletionService userDeletionService;

    @GetMapping
    public ResponseEntity<UserResponse> getMe(@AuthenticationPrincipal Jwt token) {

        UserResponse user = userService.getUser(currentUserId(token));

        return ResponseEntity.ok(user);

    }

    @PatchMapping
    public ResponseEntity<UserResponse> updateMe(@AuthenticationPrincipal Jwt token,
                                                @Valid @RequestBody UpdateUserRequest request) {

        UserResponse user = userService.updateUser(currentUserId(token), request);

        return ResponseEntity.ok(user);

    }

    // Does not delete anything: it starts the grace period. 202 with the account as it now stands, so the frontend
    // can show the deletion date straight away; a repeat while scheduled answers the same date.
    @DeleteMapping
    public ResponseEntity<UserResponse> deleteMe(@AuthenticationPrincipal Jwt token) {

        UserResponse user = userDeletionService.scheduleDeletion(currentUserId(token));

        return ResponseEntity.status(HttpStatus.ACCEPTED).body(user);
    }

    @PostMapping("/deletion/cancel")
    public ResponseEntity<Void> cancelDeletion(@AuthenticationPrincipal Jwt token) {

        userDeletionService.cancelDeletion(currentUserId(token));

        return ResponseEntity.noContent().build();
    }

    private UUID currentUserId(Jwt token) {
        return UUID.fromString(Objects.requireNonNull(token.getSubject())); // Fail loudly if null. Should not happen.
    }
}
