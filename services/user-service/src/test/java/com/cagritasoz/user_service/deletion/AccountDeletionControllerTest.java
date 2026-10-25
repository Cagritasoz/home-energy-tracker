package com.cagritasoz.user_service.deletion;

import com.cagritasoz.user_service.testsupport.data.UserFixtures;
import com.cagritasoz.user_service.testsupport.jwt.JwtFixtures;
import com.cagritasoz.user_service.user.AccountDeletedException;
import com.cagritasoz.user_service.user.UserResponse;
import com.cagritasoz.user_service.user.UserStatus;
import com.cagritasoz.user_service.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.time.Instant;
import java.util.UUID;

import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// Same standalone MockMvc setup as UserProfileControllerTest (one controller, the real exception handler, the
// argument resolver that reads @AuthenticationPrincipal from SecurityContextHolder, a hand-set JWT, no
// ApplicationContext and no provisioning interceptor); the reasons for each piece are explained there.
@ExtendWith(MockitoExtension.class)
class AccountDeletionControllerTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    @Mock
    private DeletionSchedulingService deletionSchedulingService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUpMockMvc() {

        mockMvc = MockMvcBuilders.standaloneSetup(new AccountDeletionController(deletionSchedulingService))
                .setControllerAdvice(new GlobalExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }

    @BeforeEach
    void authenticateAsArthurMorgan() {

        Jwt token = JwtFixtures.validUser(USER_ID).build();

        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
    }

    @AfterEach
    void clearSecurityContext() {

        SecurityContextHolder.clearContext();

    }

    // Starting the grace period deletes nothing, so the answer is 202 with the account as it now stands.
    @Test
    void deleteMe_schedulesTheDeletionAndReturns202WithTheDeadline() throws Exception {

        when(deletionSchedulingService.scheduleDeletion(USER_ID)).thenReturn(scheduledResponse());

        mockMvc.perform(delete("/api/v1/users/me"))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.status").value("DELETION_SCHEDULED"))
                .andExpect(jsonPath("$.deletionScheduledFor").value("2026-02-14T23:50:00Z"));

        verify(deletionSchedulingService).scheduleDeletion(USER_ID);
    }

    @Test
    void deleteMe_accountAlreadyDeleting_returns410() throws Exception {

        when(deletionSchedulingService.scheduleDeletion(USER_ID)).thenThrow(new AccountDeletedException());

        mockMvc.perform(delete("/api/v1/users/me"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.type").value("https://home-energy-tracker/problems/account-deleted"));
    }

    @Test
    void cancelDeletion_returns204WithNoBody() throws Exception {

        mockMvc.perform(post("/api/v1/users/me/deletion/cancel"))
                .andExpect(status().isNoContent())
                .andExpect(content().string(""));

        verify(deletionSchedulingService).cancelDeletion(USER_ID);
    }

    @Test
    void cancelDeletion_afterTheDeadline_returns409() throws Exception {

        doThrow(new DeletionDeadlinePassedException()).when(deletionSchedulingService).cancelDeletion(USER_ID);

        mockMvc.perform(post("/api/v1/users/me/deletion/cancel"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.type").value("https://home-energy-tracker/problems/deletion-deadline-passed"))
                .andExpect(jsonPath("$.title").value("Deletion deadline passed"));
    }

    @Test
    void cancelDeletion_accountAlreadyDeleting_returns410() throws Exception {

        doThrow(new AccountDeletedException()).when(deletionSchedulingService).cancelDeletion(USER_ID);

        mockMvc.perform(post("/api/v1/users/me/deletion/cancel"))
                .andExpect(status().isGone());
    }

    private UserResponse scheduledResponse() {

        return UserResponse.builder()
                .id(USER_ID)
                .email(UserFixtures.ARTHUR_MORGAN_EMAIL)
                .displayName(UserFixtures.ARTHUR_MORGAN_NAME)
                .timezone("UTC")
                .status(UserStatus.DELETION_SCHEDULED)
                .deletionScheduledFor(Instant.parse("2026-02-14T23:50:00Z"))
                .build();
    }
}
