package com.cagritasoz.user_service.service;

import com.cagritasoz.user_service.dto.UpdateUserRequest;
import com.cagritasoz.user_service.dto.UserResponse;
import com.cagritasoz.user_service.entity.User;
import com.cagritasoz.user_service.exception.AccountDeletedException;
import com.cagritasoz.user_service.exception.DeletionScheduledException;
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

    private final OutboxService outboxService;

    @Transactional(readOnly = true)
    public UserResponse getUser(UUID id) {

        return UserResponse.from(findUser(id));

    }

    @Transactional
    public UserResponse updateUser(UUID id, UpdateUserRequest request) {

        User user = findUser(id);

        // The interceptor's check committed before this transaction began; a deletion request can land in between,
        // so the write rules are enforced here, on the row this transaction is about to change. A concurrent
        // schedule that commits after this read but before the save moves the version, and @Version turns the
        // save into a 409 instead of a silent overwrite.
        switch (user.getStatus()) {

            case ACTIVE -> { }

            // During the grace period the account is read-only; cancelling the deletion makes the same request succeed.
            case DELETION_SCHEDULED -> throw new DeletionScheduledException();

            case DELETING, DELETED -> throw new AccountDeletedException();

        }

        Long versionBefore = user.getVersion();

        if (request.displayName() != null) {

            user.setDisplayName(request.displayName().strip());

        }

        if (request.timezone() != null) {

            user.setTimezone(request.timezone());

        }

        User synced = userRepository.saveAndFlush(user); // If nothing changes, version is not changed.

        if(!synced.getVersion().equals(versionBefore)) { // Insert outbox only if an UPDATE is issued.
            outboxService.recordUserUpdated(synced);
        }
        return UserResponse.from(synced);
    }

    private User findUser(UUID id) {

        return userRepository.findById(id)
                .orElseThrow(UserNotFoundException::new);
    }
}
