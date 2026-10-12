package com.cagritasoz.user_service.config.security;

import com.cagritasoz.user_service.testsupport.JwtFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakRealmRoleConverterTest {

    private final KeycloakRealmRoleConverter converter = new KeycloakRealmRoleConverter();

    private Jwt.Builder token() {
        return JwtFixtures.minimalToken(UUID.randomUUID());
    }

    private List<String> authorities(Jwt jwt) {
        Collection<GrantedAuthority> result = converter.convert(jwt);
        return result.stream().map(GrantedAuthority::getAuthority).toList();
    }

    @Test
    void convert_realmRoles_becomeRolePrefixedAuthorities() {
        Jwt jwt = token().claim("realm_access", Map.of("roles", List.of("user", "admin"))).build();

        assertThat(authorities(jwt)).containsExactlyInAnyOrder("ROLE_user", "ROLE_admin");
    }

    @Test
    void convert_roleCaseIsPreserved() {
        Jwt jwt = token().claim("realm_access", Map.of("roles", List.of("User"))).build();

        assertThat(authorities(jwt)).containsExactly("ROLE_User");
    }

    @Test
    void convert_noRealmAccessClaim_givesNoAuthorities() {
        assertThat(authorities(token().build())).isEmpty();
    }

    @Test
    void convert_realmAccessWithoutRoles_givesNoAuthorities() {
        Jwt jwt = token().claim("realm_access", Map.of("other", "x")).build();

        assertThat(authorities(jwt)).isEmpty();
    }

    @Test
    void convert_rolesIsNotACollection_givesNoAuthorities() {
        Jwt jwt = token().claim("realm_access", Map.of("roles", "admin")).build();

        assertThat(authorities(jwt)).isEmpty();
    }

    @Test
    void convert_emptyRoles_givesNoAuthorities() {
        Jwt jwt = token().claim("realm_access", Map.of("roles", List.of())).build();

        assertThat(authorities(jwt)).isEmpty();
    }

    @Test
    void convert_nonStringRoleElements_areStringified() {
        Jwt jwt = token().claim("realm_access", Map.of("roles", List.of(42, true))).build();

        assertThat(authorities(jwt)).containsExactlyInAnyOrder("ROLE_42", "ROLE_true");
    }

    @Test
    void convert_scopesAreKeptNextToRoles() {
        Jwt jwt = token()
                .claim("realm_access", Map.of("roles", List.of("user")))
                .claim("scope", "email profile")
                .build();

        assertThat(authorities(jwt)).containsExactlyInAnyOrder("ROLE_user", "SCOPE_email", "SCOPE_profile");
    }

    @Test
    void convert_scopeNamedLikeARole_isNotARole() {
        Jwt jwt = token().claim("scope", "admin").build();

        assertThat(authorities(jwt)).containsExactly("SCOPE_admin");
    }

    @Test
    void convert_clientRolesInResourceAccess_areIgnored() {
        Jwt jwt = token()
                .claim("resource_access", Map.of("user-service", Map.of("roles", List.of("admin"))))
                .build();

        assertThat(authorities(jwt)).isEmpty();
    }
}
