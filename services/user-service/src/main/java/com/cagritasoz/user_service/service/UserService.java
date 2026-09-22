package com.cagritasoz.user_service.service;

import com.cagritasoz.user_service.dto.UpdateUserRequest;
import com.cagritasoz.user_service.dto.UserResponse;
import com.cagritasoz.user_service.entity.User;
import com.cagritasoz.user_service.exception.UserNotFoundException;
import com.cagritasoz.user_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public UserResponse getUser(UUID id) {

        return toResponse(findUser(id));
    }

    @Transactional
    public UserResponse updateUser(UUID id, UpdateUserRequest request) {

        User user = findUser(id);

        if (request.displayName() != null) {

            user.setDisplayName(request.displayName().strip());

        }

        if (request.timezone() != null) {

            user.setTimezone(request.timezone());

        }
        return toResponse(userRepository.saveAndFlush(user));
    }

    @Transactional
    public void requestDeletion(UUID id) {

        if (userRepository.markDeleting(id) == 1) { // Winner transaction, end request.

            return;

        }

        // 0 rows changed: either the account was already past ACTIVE (nothing to do) or there is
        // no such account at all. Only the second case is an error, and this extra query runs
        // only on this rare path.
        if (!userRepository.existsById(id)) {

            throw new UserNotFoundException();

        }
    }

    private User findUser(UUID id) {

        return userRepository.findById(id)
                .orElseThrow(UserNotFoundException::new);
    }

    private UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .timezone(user.getTimezone())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}

