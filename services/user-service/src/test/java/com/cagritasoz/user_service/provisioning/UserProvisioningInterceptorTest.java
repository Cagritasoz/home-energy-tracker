package com.cagritasoz.user_service.provisioning;

import com.cagritasoz.user_service.testsupport.jwt.JwtFixtures;
import com.cagritasoz.user_service.user.AccountDeletedException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.AuthorityUtils;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class UserProvisioningInterceptorTest {

    @Mock
    private UserProvisioningService provisioningService;

    @InjectMocks
    private UserProvisioningInterceptor interceptor;

    private final MockHttpServletRequest request = new MockHttpServletRequest();

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private boolean preHandle() {
        return interceptor.preHandle(request, response, new Object());
    }

    @Test
    void preHandle_jwtAuthentication_provisionsWithThatTokenAndContinues() {
        Jwt jwt = JwtFixtures.validUser(UUID.randomUUID()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_user")));

        assertThat(preHandle()).isTrue();

        verify(provisioningService).ensureUsable(jwt);
    }

    @Test
    void preHandle_jwtWithoutTheUserRole_doesNotProvisionAndContinues() {
        Jwt jwt = JwtFixtures.validUser(UUID.randomUUID()).build();
        SecurityContextHolder.getContext().setAuthentication(
                new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_admin", "SCOPE_email")));

        assertThat(preHandle()).isTrue();

        verifyNoInteractions(provisioningService);
    }

    @Test
    void preHandle_jwtWithoutAnyAuthority_doesNotProvisionAndContinues() {
        Jwt jwt = JwtFixtures.validUser(UUID.randomUUID()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));

        assertThat(preHandle()).isTrue();

        verifyNoInteractions(provisioningService);
    }

    @Test
    void preHandle_noAuthentication_doesNothingAndContinues() {
        assertThat(preHandle()).isTrue();

        verifyNoInteractions(provisioningService);
    }

    @Test
    void preHandle_anonymousAuthentication_doesNothingAndContinues() {
        SecurityContextHolder.getContext().setAuthentication(new AnonymousAuthenticationToken(
                "key", "anonymous", AuthorityUtils.createAuthorityList("ROLE_ANONYMOUS")));

        assertThat(preHandle()).isTrue();

        verifyNoInteractions(provisioningService);
    }

    @Test
    void preHandle_nonJwtAuthentication_doesNothingAndContinues() {
        SecurityContextHolder.getContext().setAuthentication(
                UsernamePasswordAuthenticationToken.authenticated("someone", "n/a", AuthorityUtils.NO_AUTHORITIES));

        assertThat(preHandle()).isTrue();

        verifyNoInteractions(provisioningService);
    }

    @Test
    void preHandle_provisioningRejectsTheAccount_exceptionPropagates() {
        Jwt jwt = JwtFixtures.validUser(UUID.randomUUID()).build();
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt, AuthorityUtils.createAuthorityList("ROLE_user")));
        doThrow(new AccountDeletedException()).when(provisioningService).ensureUsable(jwt);

        assertThatThrownBy(this::preHandle).isInstanceOf(AccountDeletedException.class);
    }

    @Test
    void preHandle_anyNullArgument_isRejectedWithANullPointerException() {
        assertThatNullPointerException().isThrownBy(() -> interceptor.preHandle(null, response, new Object()));
        assertThatNullPointerException().isThrownBy(() -> interceptor.preHandle(request, null, new Object()));
        assertThatNullPointerException().isThrownBy(() -> interceptor.preHandle(request, response, null));
    }
}
