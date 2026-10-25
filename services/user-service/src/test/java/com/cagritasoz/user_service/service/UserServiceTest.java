package com.cagritasoz.user_service.service;

import com.cagritasoz.user_service.dto.UpdateUserRequest;
import com.cagritasoz.user_service.dto.UserResponse;
import com.cagritasoz.user_service.entity.User;
import com.cagritasoz.user_service.exception.AccountDeletedException;
import com.cagritasoz.user_service.exception.DeletionScheduledException;
import com.cagritasoz.user_service.exception.UserNotFoundException;
import com.cagritasoz.user_service.model.UserStatus;
import com.cagritasoz.user_service.repository.UserRepository;
import com.cagritasoz.user_service.testsupport.UserFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

// Layer 1: no Spring context, no database, no Kafka - just this
// one class and a mocked UserRepository, so every test here runs in milliseconds. What this layer
// deliberately does NOT prove: that markDeleting's SQL actually behaves the way its comment
// describes, that uq_users_email really rejects a duplicate, that the V6 trigger really fires -
// those need a real Postgres (Layer 2, Testcontainers), which is the next thing to add.
//
// @ExtendWith(MockitoExtension.class) wires @Mock/@InjectMocks and, importantly, fails the test if
// a stubbed method (when(...)) is never actually called - that catches a stub you copy-pasted into
// the wrong test.
@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    // A fake UserRepository. No Postgres, no Spring Data machinery - every method call has to be
    // told what to return with when(...), or it comes back null/0/empty by default.
    @Mock
    private UserRepository userRepository;

    @Mock
    private OutboxService outboxService;

    // Builds a real UserService, but hands it the @Mock above instead of a real
    // UserRepository bean. Works here because UserService has exactly one dependency injected
    // through its constructor (@RequiredArgsConstructor) - InjectMocks matches mocks to
    // constructor parameters by type.
    @InjectMocks
    private UserService userService;

    // Just the id override - UserFixtures.activeUser() already defaults to Arthur Morgan, and
    // this class only ever needs the one person.
    private User activeUser() {
        return UserFixtures.activeUser()
                .id(USER_ID)
                .build();
    }

    @Test
    void getUser_existingId_returnsMappedResponse() {
        // Arrange: tell the mock what to return for this specific argument.
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(activeUser()));

        // Act
        UserResponse response = userService.getUser(USER_ID);

        // Assert: the service's own mapping (toResponse) is what's under test here, not the
        // repository - so the interesting assertions are on the DTO's fields, not on the mock.
        assertThat(response.id()).isEqualTo(USER_ID);
        assertThat(response.email()).isEqualTo(UserFixtures.ARTHUR_MORGAN_EMAIL);
        assertThat(response.displayName()).isEqualTo(UserFixtures.ARTHUR_MORGAN_NAME);
        assertThat(response.status()).isEqualTo(UserStatus.ACTIVE);
        assertThat(response.deletionScheduledFor()).isNull();
    }

    // What the frontend decides the banner and the Cancel button from.
    @Test
    void getUser_scheduledAccount_returnsStatusAndDeadline() {
        Instant deadline = Instant.parse("2026-02-14T23:50:00Z");
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(UserFixtures.activeUser().id(USER_ID)
                .status(UserStatus.DELETION_SCHEDULED).deletionScheduledFor(deadline).build()));

        UserResponse response = userService.getUser(USER_ID);

        assertThat(response.status()).isEqualTo(UserStatus.DELETION_SCHEDULED);
        assertThat(response.deletionScheduledFor()).isEqualTo(deadline);
    }

    @Test
    void getUser_unknownId_throwsUserNotFound() {
        // No when(...) for this id: Optional-returning methods default to Optional.empty(), so
        // this line is only here to make the "there is nothing to find" intent explicit to a
        // reader, not because Mockito requires it.
        when(userRepository.findById(USER_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.getUser(USER_ID))
                .isInstanceOf(UserNotFoundException.class);
    }

    @Test
    void updateUser_displayNameOnly_leavesTimezoneUnchangedAndRecordsUpdatedEvent() {
        User existing = activeUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));

        // saveAndFlush is stubbed to hand back the same entity it was called with, but with its
        // version bumped - mirroring what Hibernate's own dirty checking really does when a field
        // actually changed (confirmed empirically, not assumed: see the Layer 2 probe this test
        // class's own comment references). updateUser() only records an event when the version
        // moved, so a stub that left it unchanged would silently defeat that check.
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> {
            User saved = invocation.getArgument(0);
            saved.setVersion(saved.getVersion() + 1);
            return saved;
        });

        UserResponse response = userService.updateUser(USER_ID,
                UpdateUserRequest.builder().displayName("  " + UserFixtures.LEON_KENNEDY_NAME + "  ").build());

        assertThat(response.displayName()).isEqualTo(UserFixtures.LEON_KENNEDY_NAME); // stripped
        assertThat(response.timezone()).isEqualTo("UTC"); // untouched: PATCH semantics

        verify(outboxService).recordUserUpdated(existing);
    }

    @Test
    void updateUser_emptyRequest_isANoOpButStillSaves() {
        User existing = activeUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
        // Nothing changes, so a real saveAndFlush would leave the version exactly as it was -
        // this stub mirrors that (unlike the test above, no version bump), which is what makes
        // this test actually prove the no-op case, not just assert it by naming the method that way.
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.updateUser(USER_ID, UpdateUserRequest.builder().build());

        assertThat(response.displayName()).isEqualTo(UserFixtures.ARTHUR_MORGAN_NAME);
        assertThat(response.timezone()).isEqualTo("UTC");

        // The whole point of the no-op case: an unchanged save must not announce a change that
        // never happened.
        verifyNoInteractions(outboxService);
    }

    // The race: PATCH passed the interceptor while ACTIVE, a deletion request then committed, and only now does
    // updateUser read the row. Scheduled means read-only (409, cancel and retry); DELETING or DELETED means gone (410).
    @Test
    void updateUser_scheduledAccount_throwsDeletionScheduledAndWritesNothing() {
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(UserFixtures.activeUser().id(USER_ID).status(UserStatus.DELETION_SCHEDULED).build()));

        assertThatThrownBy(() -> userService.updateUser(USER_ID,
                UpdateUserRequest.builder().displayName(UserFixtures.LEON_KENNEDY_NAME).build()))
                .isInstanceOf(DeletionScheduledException.class);

        verify(userRepository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxService);
    }

    @ParameterizedTest(name = "status={0}")
    @EnumSource(value = UserStatus.class, names = {"DELETING", "DELETED"})
    void updateUser_deletingOrDeletedAccount_throwsAccountDeletedAndWritesNothing(UserStatus status) {
        when(userRepository.findById(USER_ID))
                .thenReturn(Optional.of(UserFixtures.activeUser().id(USER_ID).status(status).build()));

        assertThatThrownBy(() -> userService.updateUser(USER_ID,
                UpdateUserRequest.builder().displayName(UserFixtures.LEON_KENNEDY_NAME).build()))
                .isInstanceOf(AccountDeletedException.class);

        verify(userRepository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxService);
    }
}
