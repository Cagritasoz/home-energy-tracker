package com.cagritasoz.user_service.security;

import com.cagritasoz.user_service.config.security.KeycloakRealmRoleConverter;
import com.cagritasoz.user_service.config.security.ProblemDetailAccessDeniedHandler;
import com.cagritasoz.user_service.config.security.ProblemDetailAuthenticationEntryPoint;
import com.cagritasoz.user_service.controller.UserAdminController;
import com.cagritasoz.user_service.controller.UserController;
import com.cagritasoz.user_service.dto.UserAdminResponse;
import com.cagritasoz.user_service.dto.UserResponse;
import com.cagritasoz.user_service.exception.GlobalExceptionHandler;
import com.cagritasoz.user_service.service.UserAdminService;
import com.cagritasoz.user_service.service.UserProvisioningService;
import com.cagritasoz.user_service.service.UserService;
import com.cagritasoz.user_service.testsupport.LocalJwks;
import com.cagritasoz.user_service.testsupport.TestTokens;
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

@WebMvcTest(controllers = {UserController.class, UserAdminController.class})
@Import({
        KeycloakRealmRoleConverter.class,
        ProblemDetailAuthenticationEntryPoint.class,
        ProblemDetailAccessDeniedHandler.class,
        GlobalExceptionHandler.class,
        MethodSecurityTest.PermitAllUrlRules.class // Import the modified FilterChain instead of production FilterChain.
})
@MockitoBean(types = {UserService.class, UserAdminService.class, UserProvisioningService.class})
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
    private UserService userService;

    @Autowired
    private UserAdminService userAdminService;

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
        when(userService.getUser(any())).thenReturn(UserResponse.builder().build());
        when(userService.updateUser(any(), any())).thenReturn(UserResponse.builder().build());
        when(userAdminService.getUser(any())).thenReturn(UserAdminResponse.builder().build());
        when(userAdminService.listUsers(anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of()));
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
        verifyNoInteractions(userAdminService);
    }

    @ParameterizedTest
    @MethodSource("customerRoutes")
    void userController_rejectsAnAdminTokenEvenWhenTheUrlRulesAllowEverything(Route route) throws Exception {
        call(route, TestTokens.admin(ADMIN_ID).bearer())
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.type").value("https://home-energy-tracker/problems/forbidden"));
        verifyNoInteractions(userService);
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
        verifyNoInteractions(userAdminService);
    }

    @ParameterizedTest
    @MethodSource("customerRoutes")
    void userController_rejectsAnAnonymousCallerWith401(Route route) throws Exception {
        call(route, null).andExpect(status().isUnauthorized());
        verifyNoInteractions(userService);
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
