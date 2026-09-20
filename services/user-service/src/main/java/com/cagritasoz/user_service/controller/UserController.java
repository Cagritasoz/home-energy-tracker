package com.cagritasoz.user_service.controller;

import com.cagritasoz.user_service.dto.UpdateUserRequest;
import com.cagritasoz.user_service.dto.UserResponse;
import com.cagritasoz.user_service.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

import java.util.Objects;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/users/me")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

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

    @DeleteMapping
    public ResponseEntity<Void> deleteMe(@AuthenticationPrincipal Jwt token) {

        userService.requestDeletion(currentUserId(token));

        return ResponseEntity.status(HttpStatus.ACCEPTED).build();
    }

    private UUID currentUserId(Jwt token) {
        return UUID.fromString(Objects.requireNonNull(token.getSubject())); // Fail loudly if null. Should not happen.
    }
}

