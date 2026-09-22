package com.cagritasoz.user_service.service;

import com.cagritasoz.user_service.dto.UserAdminResponse;
import com.cagritasoz.user_service.entity.User;
import com.cagritasoz.user_service.exception.UserNotFoundException;
import com.cagritasoz.user_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

// Operations an administrator performs on any account, as opposed to UserService, where a user
// works on their own. The ids here come from the URL, not from the JWT subject.
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public UserAdminResponse getUser(UUID id) {

        User user = userRepository.findById(id)
                .orElseThrow(UserNotFoundException::new);

        return toResponse(user);
    }

    // The sort is fixed instead of coming from the request: a client-supplied sort property that
    // doesn't exist would end in a 500, and the id tiebreaker keeps page boundaries stable when
    // several accounts share a created_at.
    @Transactional(readOnly = true)
    public Page<UserAdminResponse> listUsers(int page, int size) {

        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));

        return userRepository.findAll(pageRequest).map(this::toResponse);
    }

    private UserAdminResponse toResponse(User user) {
        return UserAdminResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .timezone(user.getTimezone())
                .status(user.getStatus())
                .version(user.getVersion())
                .devicesDeleted(user.isDevicesDeleted())
                .keycloakDisabledAt(user.getKeycloakDisabledAt())
                .deletionRequestedAt(user.getDeletionRequestedAt())
                .deletedAt(user.getDeletedAt())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
