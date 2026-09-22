package com.cagritasoz.user_service.service;

import com.cagritasoz.user_service.dto.UpdateUserRequest;
import com.cagritasoz.user_service.dto.UserResponse;
import com.cagritasoz.user_service.entity.User;
import com.cagritasoz.user_service.exception.UserNotFoundException;
import com.cagritasoz.user_service.repository.UserRepository;
import com.cagritasoz.user_service.testsupport.UserFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
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

    // Builds a real UserService, but hands it the @Mock above instead of a real
    // UserRepository bean. Works here because UserService has exactly one dependency injected
    // through its constructor (@RequiredArgsConstructor) - InjectMocks matches mocks to
    // constructor parameters by type.
    @InjectMocks
    private UserService userService;

    // Fields overwritten. Last one wins.
    private User activeUser() {
        return UserFixtures.activeUser()
                .id(USER_ID)
                .email("ada@example.com")
                .displayName("Ada Wong")
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
        assertThat(response.email()).isEqualTo("ada@example.com");
        assertThat(response.displayName()).isEqualTo("Ada Wong");
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
    void updateUser_displayNameOnly_leavesTimezoneUnchanged() {
        User existing = activeUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
        // saveAndFlush is stubbed to hand back whatever entity it was called with - close enough
        // to what Hibernate really does here (it mutates and returns the same managed instance)
        // without needing a real EntityManager.
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.updateUser(USER_ID,
                UpdateUserRequest.builder().displayName("  Ada Wong  ").build());

        assertThat(response.displayName()).isEqualTo("Ada Wong"); // stripped
        assertThat(response.timezone()).isEqualTo("UTC");             // untouched: PATCH semantics
    }

    @Test
    void updateUser_emptyRequest_isANoOpButStillSaves() {
        User existing = activeUser();
        when(userRepository.findById(USER_ID)).thenReturn(Optional.of(existing));
        when(userRepository.saveAndFlush(any(User.class))).thenAnswer(invocation -> invocation.getArgument(0));

        UserResponse response = userService.updateUser(USER_ID, UpdateUserRequest.builder().build());

        assertThat(response.displayName()).isEqualTo("Ada Wong");
        assertThat(response.timezone()).isEqualTo("UTC");
    }

    @Test
    void requestDeletion_activeAccount_stopsAfterMarkDeletingWins() {
        // markDeleting returning 1 means "this call won the compare-and-set" (see its own comment
        // in UserRepository) - requestDeletion must not go on to call existsById in that case.
        when(userRepository.markDeleting(USER_ID)).thenReturn(1);

        userService.requestDeletion(USER_ID);

        verify(userRepository, never()).existsById(any());
    }

    @Test
    void requestDeletion_alreadyDeleting_isASilentNoOp() {
        // 0 rows changed, but the user does exist - requestDeletion must return quietly, not throw.
        when(userRepository.markDeleting(USER_ID)).thenReturn(0);
        when(userRepository.existsById(USER_ID)).thenReturn(true);

        userService.requestDeletion(USER_ID);
        // Nothing to assert on a void, idempotent call beyond "it didn't throw" - if it had thrown,
        // this test would already have failed before reaching here.
    }

    @Test
    void requestDeletion_unknownId_throwsUserNotFound() {
        when(userRepository.markDeleting(USER_ID)).thenReturn(0);
        when(userRepository.existsById(USER_ID)).thenReturn(false);

        assertThatThrownBy(() -> userService.requestDeletion(USER_ID))
                .isInstanceOf(UserNotFoundException.class);
    }
}
