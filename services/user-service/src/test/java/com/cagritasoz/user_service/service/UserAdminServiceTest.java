package com.cagritasoz.user_service.service;

import com.cagritasoz.user_service.dto.UserAdminResponse;
import com.cagritasoz.user_service.entity.User;
import com.cagritasoz.user_service.exception.UserNotFoundException;
import com.cagritasoz.user_service.repository.UserRepository;
import com.cagritasoz.user_service.testsupport.UserFixtures;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserAdminServiceTest {

    @Mock
    private UserRepository userRepository;

    @InjectMocks
    private UserAdminService userAdminService;

    private static final UUID USER_ID_1 = UUID.fromString("11111111-1111-1111-1111-111111111111");
    private static final UUID USER_ID_2 = UUID.fromString("22222222-2222-2222-2222-222222222222");
    private static final UUID USER_ID_3 = UUID.fromString("33333333-3333-3333-3333-333333333333");

    // Just the id and createdAt overrides - UserFixtures.deletedUser() already defaults to Arthur
    // Morgan (via activeUser()), and every other saga-bookkeeping column this class cares about
    // (status, version, devicesDeleted, the timestamps) already comes from that default.
    private User deletedUser() {
        return UserFixtures.deletedUser()
                .id(USER_ID_1)
                .createdAt(Instant.parse("2025-10-15T14:32:18Z")) // predates UserFixtures' default
                .build();
    }

    @Test
    void getUser_existingId_returnsMappedAdminResponse() {

        when(userRepository.findById(USER_ID_1)).thenReturn(Optional.of(deletedUser()));

        UserAdminResponse response = userAdminService.getUser(USER_ID_1);

        assertThat(response).usingRecursiveComparison().isEqualTo(deletedUser());
    }

    @Test
    void getUser_unknownId_throwsUserNotFound() {

        when(userRepository.findById(USER_ID_1)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userAdminService.getUser(USER_ID_1))
                .isInstanceOf(UserNotFoundException.class);
    }

    // listUsers's return value can't show whether the sort is really fixed - Page<UserAdminResponse>
    // looks the same either way. The only place that's visible is the Pageable object the service
    // builds and passes to the repository, so this test has to capture the ARGUMENT the mock was
    // called with, not just its return value.
    @Test
    void listUsers_buildsFixedSortRegardlessOfInput() {
        // findAll(Pageable) needs a real Page back, not a bare list - an empty one is fine here
        // since this test only cares about what Pageable was sent to the repository, not about
        // what the page contains.
        when(userRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of()));

        userAdminService.listUsers(2, 10);

        // ArgumentCaptor.forClass(...) creates an empty box of the given type.
        // pageableCaptor.capture() is passed to verify(...) in place of a normal matcher (like
        // any()) - it still matches any Pageable, but as a side effect it also remembers the
        // actual object that was passed in, so it can be inspected afterward.
        ArgumentCaptor<Pageable> pageableCaptor = ArgumentCaptor.forClass(Pageable.class);
        verify(userRepository).findAll(pageableCaptor.capture());
        Pageable usedPageable = pageableCaptor.getValue();

        assertThat(usedPageable.getPageNumber()).isEqualTo(2);
        assertThat(usedPageable.getPageSize()).isEqualTo(10);
        assertThat(usedPageable.getSort()).isEqualTo(
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id")));
    }

    @Test
    void listUsers_mappingRunsPerElement() {

        User user1 = deletedUser();

        User user2 = UserFixtures.activeUser()
                .id(USER_ID_2)
                .email(UserFixtures.LEON_KENNEDY_EMAIL)
                .displayName(UserFixtures.LEON_KENNEDY_NAME)
                .build();

        User user3 = UserFixtures.deletedUser()
                .id(USER_ID_3)
                .email(UserFixtures.JOHN_MARSTON_EMAIL)
                .displayName(UserFixtures.JOHN_MARSTON_NAME)
                .deletionRequestedAt(Instant.parse("2026-01-31T23:31:42Z"))
                .build();

        when(userRepository.findAll(any(Pageable.class))).thenReturn(new PageImpl<>(List.of(user1, user2, user3)));

        Page<UserAdminResponse> page = userAdminService.listUsers(0, 20);

        assertThat(page.getContent())
                .extracting(UserAdminResponse::id, UserAdminResponse::email, UserAdminResponse::deletionRequestedAt)
                .containsExactly(
                        tuple(user1.getId(), user1.getEmail(), user1.getDeletionRequestedAt()), // User class is not a record! Traditional getters are used.
                        tuple(user2.getId(), user2.getEmail(), user2.getDeletionRequestedAt()),
                        tuple(user3.getId(), user3.getEmail(), user3.getDeletionRequestedAt())
                );
    }
}
