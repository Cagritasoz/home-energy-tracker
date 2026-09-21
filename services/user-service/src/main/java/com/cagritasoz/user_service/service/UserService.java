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

// Self-service operations on the caller's own account (what an administrator can do to any account
// lives in UserAdminService). There is no createUser here on purpose: accounts are created
// just-in-time by UserProvisioningService the first time a valid token shows up, and there is no
// delete either - see requestDeletion for why. Every method takes the id from the validated JWT
// subject (the controller passes it in), never from the request body.
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional(readOnly = true)
    public UserResponse getUser(UUID id) {

        return toResponse(findUser(id));
    }

    // PATCH semantics: only the fields present (non-null) in the request change, everything else
    // is left alone, and an empty body is a valid no-op. Email is deliberately not updatable
    // here - Keycloak owns it.
    //
    // saveAndFlush instead of relying on dirty checking at commit: the UPDATE has to run inside
    // this method so the V6 trigger stamps updated_at and Hibernate re-reads it (@Generated)
    // before the response below is built. With a plain managed entity the flush would only happen
    // at commit - after toResponse already copied the previous updated_at into the response.
    // When nothing actually changed Hibernate issues no UPDATE at all, so updated_at and version
    // stay where they were.
    //
    // A concurrent change to the same row makes the flush fail the @Version check
    // (OptimisticLockingFailureException) - nothing is written, the caller can re-read and retry.
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

    // Soft delete, step one of the saga: only marks the account as DELETING and records when the
    // request was made. Nothing is removed here - the finalizer later disables the Keycloak
    // user, waits for devices to be cleaned up and flips the row to DELETED. Once the status is
    // DELETING the provisioning interceptor rejects every further token for this account, which
    // is what actually cuts the user off.
    //
    // Idempotent: a repeated request for an account that is already DELETING or DELETED changes
    // nothing (in particular it must not push deletion_requested_at forward, which would restart
    // the finalizer's grace period). That also holds for two requests racing each other: the
    // single conditional UPDATE in markDeleting lets exactly one of them win, and the other one
    // simply finds nothing left to change - no exception, no lost update.
    // The entity is deliberately not loaded here: the status change and its timestamp are done in
    // SQL so the database clock is the only one involved (see markDeleting).
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

