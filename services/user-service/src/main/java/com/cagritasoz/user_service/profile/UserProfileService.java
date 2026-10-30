package com.cagritasoz.user_service.profile;

import com.cagritasoz.user_service.outbox.OutboxService;
import com.cagritasoz.user_service.user.AccountDeletedException;
import com.cagritasoz.user_service.user.DeletionScheduledException;
import com.cagritasoz.user_service.user.User;
import com.cagritasoz.user_service.user.UserNotFoundException;
import com.cagritasoz.user_service.user.UserRepository;
import com.cagritasoz.user_service.user.UserResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
public class UserProfileService {

    private final UserRepository userRepository;

    private final OutboxService outboxService;

    @Transactional(readOnly = true)
    public UserResponse getUser(UUID id) {

        return UserResponse.from(findUser(id));

    }

    @Transactional
    public UserResponse updateUser(UUID id, UpdateUserRequest request) {

        // The row is locked from this read until the commit: the interceptor's check committed before this
        // transaction began, and a deletion request can land in between, so the write rules are enforced here, on the
        // row this transaction is about to change, and nothing can change it under the check. A schedule that comes
        // while this runs waits for the commit; one that committed first is seen by this read, and the update is
        // refused below. (Why the lock and not the version check alone: see findByIdForUpdate.)
        User user = userRepository.findByIdForUpdate(id)
                .orElseThrow(UserNotFoundException::new);

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
