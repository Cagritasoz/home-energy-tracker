package com.cagritasoz.user_service.profile;

import com.cagritasoz.user_service.user.UserResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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
public class UserProfileController {

    private final UserProfileService userProfileService;

    @GetMapping
    public ResponseEntity<UserResponse> getMe(@AuthenticationPrincipal Jwt token) {

        UserResponse user = userProfileService.getUser(currentUserId(token));

        return ResponseEntity.ok(user);

    }

    @PatchMapping
    public ResponseEntity<UserResponse> updateMe(@AuthenticationPrincipal Jwt token,
                                                @Valid @RequestBody UpdateUserRequest request) {

        UserResponse user = userProfileService.updateUser(currentUserId(token), request);

        return ResponseEntity.ok(user);

    }

    private UUID currentUserId(Jwt token) {
        return UUID.fromString(Objects.requireNonNull(token.getSubject())); // Fail loudly if null. Should not happen.
    }
}
