package com.cagritasoz.user_service.service;

import com.cagritasoz.user_service.dto.UserRequest;
import com.cagritasoz.user_service.dto.UserResponse;
import com.cagritasoz.user_service.entity.User;
import com.cagritasoz.user_service.event.UserChangedPayload;
import com.cagritasoz.user_service.event.UserDeletedPayload;
import com.cagritasoz.user_service.exception.DuplicateEmailException;
import com.cagritasoz.user_service.exception.UserNotFoundException;
import com.cagritasoz.user_service.model.OutboxAggregateType;
import com.cagritasoz.user_service.model.OutboxEventType;
import com.cagritasoz.user_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;
    private final OutboxService outboxService;

    @Transactional
    public UserResponse createUser(UserRequest request) {

        // Fast-path check: gives a clean, typed error in the common (non-racing) case instead of
        // letting a DB round-trip fail. NOT sufficient on its own - see the
        // DataIntegrityViolationException handler in GlobalExceptionHandler for why.
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException();
        }

        final User user = User.builder()
                .firstName(request.firstName())
                .lastName(request.lastName())
                .email(request.email())
                .address(request.address())
                .build();

        // id/createdAt/updatedAt are populated by the DB/Hibernate on save, not before.
        User saved = userRepository.save(user);

        // partitionKey == aggregateId here: a USER event's own id IS its ordering group - only
        // ALERT_RULE_* events use a different value (their owning user's id) for partitionKey.
        // Instant.now() rather than saved.getCreatedAt(): both are effectively "now" (save() on
        // an IDENTITY entity flushes immediately, so getCreatedAt() would actually be populated
        // here too), but using the same explicit capture in every event - create, update, and
        // delete alike - avoids depending on flush timing being safe in some cases and not others
        // (see updateUser, where reading getUpdatedAt() at this point WOULD be wrong - dirty
        // checking only flushes at commit, well after this method returns).
        outboxService.recordEvent(OutboxAggregateType.USER, saved.getId(), saved.getId(), OutboxEventType.USER_CREATED,
                UserChangedPayload.builder()
                        .userId(saved.getId())
                        .firstName(saved.getFirstName())
                        .lastName(saved.getLastName())
                        .email(saved.getEmail())
                        .address(saved.getAddress())
                        .occurredAt(Instant.now())
                        .build());

        return toResponse(saved);
    }

    // Unlike getAlertRules, there's no parent to scope by and no existsById check needed -
    // users is the top-level resource. findAll() comes free from JpaRepository, no custom
    // repository method required.
    @Transactional(readOnly = true)
    public List<UserResponse> getUsers() {
        return userRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserResponse getUserById(Long id) {

        User user = userRepository.findById(id)
                .orElseThrow(UserNotFoundException::new);

        return toResponse(user);
    }

    @Transactional
    public UserResponse updateUser(Long id, UserRequest request) {

        User user = userRepository.findById(id)
                .orElseThrow(UserNotFoundException::new); // managed entity

        // Only check when the email is actually changing - otherwise a user keeping their own
        // email would always "collide" with themselves since existsByEmail() would always
        // return true for it.
        boolean emailChanged = !user.getEmail().equals(request.email());
        if (emailChanged && userRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException();
        }

        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setEmail(request.email());
        user.setAddress(request.address());

        // Instant.now(), not user.getUpdatedAt(): @UpdateTimestamp only actually stamps the new
        // value when Hibernate flushes the UPDATE, which for a managed entity with no explicit
        // save() happens at commit - after this method returns, not before. Reading
        // getUpdatedAt() here would still hold the PREVIOUS update's timestamp, not this one.
        outboxService.recordEvent(OutboxAggregateType.USER, user.getId(), user.getId(), OutboxEventType.USER_UPDATED,
                UserChangedPayload.builder()
                        .userId(user.getId())
                        .firstName(user.getFirstName())
                        .lastName(user.getLastName())
                        .email(user.getEmail())
                        .address(user.getAddress())
                        .occurredAt(Instant.now())
                        .build());

        // Managed entity - dirty checking flushes these changes at commit (UPDATE, not INSERT),
        // and @UpdateTimestamp bumps updated_at along with it. No save() call needed.
        return toResponse(user);
    }

    @Transactional
    public void deleteUser(Long id) {

        User user = userRepository.findById(id)
                .orElseThrow(UserNotFoundException::new);

        // Recorded before the delete call, but it wouldn't matter if it were after: both the
        // outbox insert and the DELETE are flushed together at this method's commit, in the same
        // transaction - there's no partial-completion window between them either way.
        //
        // This is the ONLY event emitted for this user's alert_rules disappearing too - they
        // cascade-delete via V3's DB-level FK, invisible to this method (Java code here never
        // touches those rows), so there is no per-rule ALERT_RULE_DELETED to emit. Any consumer
        // caching alert-rule state must treat USER_DELETED as "drop this user's rules as well" -
        outboxService.recordEvent(OutboxAggregateType.USER, user.getId(), user.getId(), OutboxEventType.USER_DELETED,
                UserDeletedPayload.builder()
                        .userId(user.getId())
                        .occurredAt(Instant.now())
                        .build());

        userRepository.delete(user);
    }

    private UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .address(user.getAddress())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
