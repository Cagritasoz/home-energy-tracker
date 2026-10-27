package com.cagritasoz.user_service.provisioning;

import com.cagritasoz.user_service.keycloak.KeycloakCircuitOpenException;
import com.cagritasoz.user_service.keycloak.KeycloakUnavailableException;
import com.cagritasoz.user_service.security.Route;
import com.cagritasoz.user_service.security.SecuritySliceTest;
import com.cagritasoz.user_service.testsupport.jwt.TestTokens;
import com.cagritasoz.user_service.user.AccountDeletedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;

import java.sql.SQLException;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ProvisioningInterceptorWiringTest extends SecuritySliceTest {

    static Stream<Route> customerRoutes() {
        return Route.CUSTOMER.stream();
    }

    static Stream<Route> adminRoutes() {
        return Route.ADMIN.stream();
    }

    static Stream<Arguments> provisioningFailures() {
        return Route.CUSTOMER.stream().flatMap(route -> Stream.of(
                Arguments.of(route, new AccountDeletedException(), 410, "account-deleted"),
                Arguments.of(route, new EmailNotVerifiedException(), 403, "email-not-verified"),
                Arguments.of(route, new MissingIdentityClaimException("email"), 403, "missing-identity-claim"),
                Arguments.of(route, new EmailBeingReleasedException(), 503, "email-being-released"),
                Arguments.of(route, new EmailAlreadyInUseException(), 409, "email-already-in-use"),
                Arguments.of(route, new KeycloakUnavailableException("down", true), 503, "identity-provider-unavailable"),
                Arguments.of(route, new KeycloakCircuitOpenException(null), 503, "identity-provider-unavailable"),
                Arguments.of(route, new DataIntegrityViolationException("x",
                        new SQLException("duplicate key value violates unique constraint", "23505")), 409, "data-conflict"),
                Arguments.of(route, new RuntimeException("db password: hunter2"), 500, "internal-error")));
    }

    @ParameterizedTest
    @MethodSource("customerRoutes")
    void customerRoute_provisionsExactlyOnceWithTheValidatedToken(Route route) throws Exception {
        String token = TestTokens.user(USER_ID).signed();

        call(route, "Bearer " + token).andExpect(status().is(route.successStatus()));

        ArgumentCaptor<Jwt> captor = ArgumentCaptor.forClass(Jwt.class);
        verify(provisioningService, times(1)).ensureUsable(captor.capture());
        assertThat(captor.getValue().getSubject()).isEqualTo(USER_ID.toString());
        assertThat(captor.getValue().getTokenValue()).isEqualTo(token);
    }

    @ParameterizedTest
    @MethodSource("adminRoutes")
    void adminRoute_neverProvisionsTheAdmin(Route route) throws Exception {
        call(route, TestTokens.admin(ADMIN_ID).bearer());

        verifyNoInteractions(provisioningService);
    }

    @ParameterizedTest
    @MethodSource("provisioningFailures")
    void provisioningFailure_isAnsweredByTheGlobalHandlerAndTheControllerIsNeverReached(
            Route route, RuntimeException failure, int expectedStatus, String expectedType) throws Exception {
        doThrow(failure).when(provisioningService).ensureUsable(any());

        var result = call(route, TestTokens.user(USER_ID).bearer())
                .andExpect(status().is(expectedStatus))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andReturn();

        assertThat(result.getResponse().getContentAsString()).contains("home-energy-tracker/problems/" + expectedType);
        assertThat(result.getResponse().getContentAsString()).doesNotContain("hunter2");
        verifyNoInteractions(userProfileService, deletionSchedulingService);
    }

    // The two answers that tell the client to come back carry Retry-After; the conflict that waiting cannot fix does not.
    @ParameterizedTest
    @MethodSource("customerRoutes")
    void provisioningFailure_thatIsWorthRetrying_carriesRetryAfter(Route route) throws Exception {
        doThrow(new EmailBeingReleasedException()).when(provisioningService).ensureUsable(any());
        call(route, TestTokens.user(USER_ID).bearer()).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "10"));

        doThrow(new KeycloakUnavailableException("down", true)).when(provisioningService).ensureUsable(any());
        call(route, TestTokens.user(USER_ID).bearer()).andExpect(status().isServiceUnavailable()).andExpect(header().string("Retry-After", "30"));

        doThrow(new EmailAlreadyInUseException()).when(provisioningService).ensureUsable(any());
        call(route, TestTokens.user(USER_ID).bearer()).andExpect(status().isConflict()).andExpect(header().doesNotExist("Retry-After"));
    }

    @Test
    void identity_comesOnlyFromTheTokenSubject() throws Exception {
        UUID someoneElse = UUID.fromString("99999999-9999-9999-9999-999999999999");
        String bearer = TestTokens.user(USER_ID).bearer();

        mockMvc.perform(get("/api/v1/users/me")
                        .queryParam("userId", someoneElse.toString())
                        .queryParam("id", someoneElse.toString())
                        .header("X-User-Id", someoneElse.toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());
        mockMvc.perform(patch("/api/v1/users/me")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"id\":\"" + someoneElse + "\",\"userId\":\"" + someoneElse + "\"}")
                        .header("X-User-Id", someoneElse.toString())
                        .header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isOk());

        verify(userProfileService).getUser(USER_ID);
        verify(userProfileService).updateUser(eq(USER_ID), any());
        verify(userProfileService, never()).getUser(someoneElse);
        verify(userProfileService, never()).updateUser(eq(someoneElse), any());
    }

    @Test
    void adminIdInThePath_isOnlyATargetNeverTheCallersIdentity() throws Exception {
        UUID target = UUID.fromString("33333333-3333-3333-3333-333333333333");

        call(Route.ADMIN_ONE, TestTokens.admin(ADMIN_ID).bearer()).andExpect(status().isOk());

        verify(adminUserService).getUser(target);
        verifyNoInteractions(userProfileService, deletionSchedulingService, provisioningService);
    }
}
