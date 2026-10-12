package com.cagritasoz.user_service.security;

import com.cagritasoz.user_service.testsupport.TestTokens;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.ResultActions;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityAuthorizationTest extends SecuritySliceTest {

    record Persona(String name, boolean customer, boolean admin, UnaryOperator<TestTokens> shape) {

        String bearer() {
            return shape.apply(TestTokens.forSubject(USER_ID)).bearer();
        }

        @Override
        public @NonNull String toString() {
            return name;
        }
    }

    static final List<Persona> PERSONAS = List.of(
            new Persona("user", true, false, t -> t.roles("user")),
            new Persona("admin", false, true, t -> t.roles("admin")),
            new Persona("user and admin", true, true, t -> t.roles("user", "admin")),
            new Persona("no roles", false, false, TestTokens::roles),
            new Persona("upper case roles", false, false, t -> t.roles("USER", "ADMIN")),
            new Persona("capitalised roles", false, false, t -> t.roles("User", "Admin")),
            new Persona("padded role names", false, false, t -> t.roles("user ", " admin")),
            new Persona("role names already prefixed", false, false, t -> t.roles("ROLE_user", "ROLE_admin")),
            new Persona("look-alike role names", false, false, t -> t.roles("users", "administrator", "superadmin")),
            new Persona("default composite only", false, false,
                    t -> t.roles("offline_access", "uma_authorization", "default-roles-energy-tracker")),
            new Persona("no realm_access claim", false, false, t -> t.without("realm_access")),
            new Persona("roles claim is a string", false, false, t -> t.claim("realm_access", Map.of("roles", "admin"))),
            new Persona("roles only as client roles", false, false, t -> t.without("realm_access")
                    .claim("resource_access", Map.of("user-service", Map.of("roles", List.of("user", "admin"))))),
            new Persona("roles only as scopes", false, false, t -> t.without("realm_access").claim("scope", "user admin"))
    );

    static Stream<Arguments> matrix() {
        return PERSONAS.stream().flatMap(persona -> Route.ALL.stream()
                .filter(route -> route != Route.UNKNOWN)
                .map(route -> Arguments.of(persona, route)));
    }

    static Stream<Persona> personas() {
        return PERSONAS.stream();
    }

    private static int expectedStatus(Persona persona, Route route) {
        if (route == Route.ADMIN_UNMAPPED) {
            return persona.admin() ? 404 : 403;
        }
        boolean allowed = route.customerApi() ? persona.customer() : persona.admin();
        return allowed ? route.successStatus() : 403;
    }

    private void assertForbidden(ResultActions result) throws Exception {
        result.andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(jsonPath("$.type").value("https://home-energy-tracker/problems/forbidden"))
                .andExpect(jsonPath("$.title").value("Forbidden"))
                .andExpect(jsonPath("$.status").value(403))
                .andExpect(jsonPath("$.detail").value("You do not have permission to perform this action."))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @ParameterizedTest(name = "{0} calling {1}")
    @MethodSource("matrix")
    void roleMatrix_decidesTheOutcomeOfEveryRoute(Persona persona, Route route) throws Exception {
        int expected = expectedStatus(persona, route);

        ResultActions result = call(route, persona.bearer());

        result.andExpect(status().is(expected));
        if (expected == 403) {
            assertForbidden(result);
            verifyNoServiceWasCalled();
        } else if (route.customerApi()) {
            verify(provisioningService).ensureUsable(any(Jwt.class));
        } else {
            verifyNoInteractions(provisioningService);
        }
    }

    @ParameterizedTest
    @MethodSource("personas")
    void anyAuthenticatedPersona_reachesAnUnknownRoute_as404(Persona persona) throws Exception {
        call(Route.UNKNOWN, persona.bearer()).andExpect(status().isNotFound());
        verifyNoServiceWasCalled();
    }

    @Test
    void adminOnAdminUnmappedRoute_getsAProblemDetail404() throws Exception {
        call(Route.ADMIN_UNMAPPED, TestTokens.admin(ADMIN_ID).bearer())
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404));
    }

    @Test
    void methodWithoutMapping_userGets405_adminGets403_anonymousGets401() throws Exception {
        mockMvc.perform(post("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, TestTokens.user(USER_ID).bearer()))
                .andExpect(status().isMethodNotAllowed());
        assertForbidden(mockMvc.perform(post("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.admin(ADMIN_ID).bearer())));
        mockMvc.perform(post("/api/v1/users/me")).andExpect(status().isUnauthorized());
    }

    @Test
    void trailingSlashOnCustomerRoute_isStillGuardedByTheRoleRule() throws Exception {
        assertForbidden(mockMvc.perform(get("/api/v1/users/me/")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.admin(ADMIN_ID).bearer())));
        mockMvc.perform(get("/api/v1/users/me/").header(HttpHeaders.AUTHORIZATION, TestTokens.user(USER_ID).bearer()))
                .andExpect(status().isNotFound());
        verifyNoInteractions(userService);
    }

    @Test
    void trailingSlashOnAdminRoute_isStillGuardedByTheRoleRule() throws Exception {
        assertForbidden(mockMvc.perform(get("/api/v1/admin/users/")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.user(USER_ID).bearer())));
        mockMvc.perform(get("/api/v1/admin/users/").header(HttpHeaders.AUTHORIZATION, TestTokens.admin(ADMIN_ID).bearer()))
                .andExpect(status().isNotFound());
        verifyNoInteractions(userAdminService);
    }

    @Test
    void differentlyCasedAdminPath_doesNotReachTheController() throws Exception {
        mockMvc.perform(get("/API/V1/ADMIN/USERS").header(HttpHeaders.AUTHORIZATION, TestTokens.user(USER_ID).bearer()))
                .andExpect(status().isNotFound());
        verifyNoServiceWasCalled();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/admin/users;x=1",
            "/api/v1/users/%2e%2e/admin/users",
            "/api/v1/users%2fme",
            "/api/v1//admin/users",
            "/api/v1/users%5cme"
    })
    void suspiciousPaths_areRejectedByTheFirewallBeforeAnyRuleRuns(String path) throws Exception {
        mockMvc.perform(get(URI.create(path)).header(HttpHeaders.AUTHORIZATION, TestTokens.user(USER_ID).bearer()))
                .andExpect(status().isBadRequest());
        verifyNoServiceWasCalled();
    }

    @ParameterizedTest
    @MethodSource("personas")
    void securityHeaders_arePresentOnEveryKindOfResponse(Persona persona) throws Exception {
        for (Route route : List.of(Route.ME_GET, Route.ADMIN_LIST)) {
            call(route, persona.bearer())
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate"));
        }
    }

    @Test
    void securityHeaders_arePresentOnUnauthorizedResponses() throws Exception {
        call(Route.ME_GET, null)
                .andExpect(status().isUnauthorized())
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(HttpHeaders.CACHE_CONTROL, "no-cache, no-store, max-age=0, must-revalidate"));
    }

    @Test
    void responses_neverCreateASessionOrSetACookie() throws Exception {
        var result = call(Route.ME_GET, TestTokens.user(USER_ID).bearer()).andExpect(status().isOk()).andReturn();

        assertThat(result.getRequest().getSession(false)).isNull();
        assertThat(result.getResponse().getHeaders(HttpHeaders.SET_COOKIE)).isEmpty();
    }

    @Test
    void mutatingCalls_workWithoutACsrfToken() throws Exception {
        String bearer = TestTokens.user(USER_ID).bearer();

        call(Route.ME_PATCH, bearer).andExpect(status().isOk());
        call(Route.ME_DELETE, bearer).andExpect(status().isAccepted());
    }
}
