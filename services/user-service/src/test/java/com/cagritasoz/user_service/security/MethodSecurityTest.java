package com.cagritasoz.user_service.security;

import com.cagritasoz.user_service.admin.AdminUserController;
import com.cagritasoz.user_service.deletion.AccountDeletionController;
import com.cagritasoz.user_service.admin.AdminUserResponse;
import com.cagritasoz.user_service.admin.AdminUserService;
import com.cagritasoz.user_service.deletion.DeletionSchedulingService;
import com.cagritasoz.user_service.profile.UserProfileController;
import com.cagritasoz.user_service.profile.UserProfileService;
import com.cagritasoz.user_service.provisioning.UserProvisioningService;
import com.cagritasoz.user_service.testsupport.jwt.LocalJwks;
import com.cagritasoz.user_service.testsupport.jwt.TestTokens;
import com.cagritasoz.user_service.user.UserResponse;
import com.cagritasoz.user_service.web.GlobalExceptionHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpHeaders;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;
import java.util.stream.Stream;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = {UserProfileController.class, AccountDeletionController.class, AdminUserController.class})
@Import({
        KeycloakRealmRoleConverter.class,
        ProblemDetailAuthenticationEntryPoint.class,
        ProblemDetailAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        MethodSecurityTest.PermitAllUrlRules.class // Import the modified FilterChain instead of production FilterChain.
})
@MockitoBean(types = {UserProfileService.class, DeletionSchedulingService.class, AdminUserService.class, UserProvisioningService.class})
class MethodSecurityTest {

    private static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID ADMIN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    // Permit all routes to test @PreAuthorize behavior.
    @TestConfiguration
    @EnableWebSecurity
    @EnableMethodSecurity
    static class PermitAllUrlRules {

        @Bean
        JwtDecoder jwtDecoder() {
            return NimbusJwtDecoder.withJwkSetUri(LocalJwks.SHARED.url()).build();
        }

        @Bean
        SecurityFilterChain permitAllChain(HttpSecurity http,
                                           JwtDecoder decoder,
                                           KeycloakRealmRoleConverter roleConverter,
                                           ProblemDetailAuthenticationEntryPoint entryPoint,
                                           ProblemDetailAccessDeniedHandler deniedHandler) throws Exception {

            JwtAuthenticationConverter converter = new JwtAuthenticationConverter();
            converter.setJwtGrantedAuthoritiesConverter(roleConverter);

            return http
                    .csrf(AbstractHttpConfigurer::disable)
                    .cors(Customizer.withDefaults())
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .oauth2ResourceServer(oauth2 -> oauth2
                            .jwt(jwt -> jwt.decoder(decoder).jwtAuthenticationConverter(converter))
                            .authenticationEntryPoint(entryPoint))
                    .exceptionHandling(ex -> ex
                            .authenticationEntryPoint(entryPoint)
                            .accessDeniedHandler(deniedHandler))
                    .build();
        }
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UserProfileService userProfileService;

    @Autowired
    private DeletionSchedulingService deletionSchedulingService;

    @Autowired
    private AdminUserService adminUserService;

    @Autowired
    private UserProvisioningService provisioningService;

    static Stream<Route> customerRoutes() {
        return Route.CUSTOMER.stream();
    }

    static Stream<Route> adminRoutes() {
        return Stream.of(Route.ADMIN_LIST, Route.ADMIN_ONE);
    }

    @BeforeEach
    void stubServices() {
        when(userProfileService.getUser(any())).thenReturn(UserResponse.builder().build());
        when(userProfileService.updateUser(any(), any())).thenReturn(UserResponse.builder().build());
        when(deletionSchedulingService.scheduleDeletion(any())).thenReturn(UserResponse.builder().build());
        when(adminUserService.getUser(any())).thenReturn(AdminUserResponse.builder().build());
        when(adminUserService.listUsers(anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of()));
    }

    private ResultActions call(Route route, String bearer) throws Exception {

        MockHttpServletRequestBuilder request = route.request();

        if (bearer != null) {
            request.header(HttpHeaders.AUTHORIZATION, bearer);
        }

        return mockMvc.perform(request);
    }

    @ParameterizedTest
    @MethodSource("adminRoutes")
    void adminController_rejectsACustomerTokenEvenWhenTheUrlRulesAllowEverything(Route route) throws Exception {
        call(route, TestTokens.user(USER_ID).bearer())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://home-energy-tracker/problems/forbidden"));
        verifyNoInteractions(adminUserService);
    }

    @ParameterizedTest
    @MethodSource("customerRoutes")
    void userController_rejectsAnAdminTokenEvenWhenTheUrlRulesAllowEverything(Route route) throws Exception {
        call(route, TestTokens.admin(ADMIN_ID).bearer())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://home-energy-tracker/problems/forbidden"));
        verifyNoInteractions(userProfileService, deletionSchedulingService);
    }

    @ParameterizedTest
    @MethodSource("customerRoutes")
    void userController_adminTokenWithoutUrlRules_isDeniedAndNeverProvisioned(Route route) throws Exception {
        call(route, TestTokens.admin(ADMIN_ID).bearer()).andExpect(status().isForbidden());

        verifyNoInteractions(provisioningService);
    }

    @ParameterizedTest
    @MethodSource("adminRoutes")
    void adminController_rejectsAnAnonymousCallerWith401(Route route) throws Exception {
        call(route, null)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE, "Bearer realm=\"energy-tracker\""));
        verifyNoInteractions(adminUserService);
    }

    @ParameterizedTest
    @MethodSource("customerRoutes")
    void userController_rejectsAnAnonymousCallerWith401(Route route) throws Exception {
        call(route, null).andExpect(status().isUnauthorized());
        verifyNoInteractions(userProfileService, deletionSchedulingService);
    }

    @ParameterizedTest
    @MethodSource("adminRoutes")
    void adminController_letsAnAdminThrough(Route route) throws Exception {
        call(route, TestTokens.admin(ADMIN_ID).bearer()).andExpect(status().isOk());
    }

    @ParameterizedTest
    @MethodSource("customerRoutes")
    void userController_letsACustomerThrough(Route route) throws Exception {
        call(route, TestTokens.user(USER_ID).bearer()).andExpect(status().is(route.successStatus()));
    }
}
