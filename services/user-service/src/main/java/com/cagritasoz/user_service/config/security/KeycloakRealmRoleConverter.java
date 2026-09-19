package com.cagritasoz.user_service.config.security;

import org.jspecify.annotations.NonNull;
import org.springframework.core.convert.converter.Converter;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtGrantedAuthoritiesConverter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Map;

@Component
public class KeycloakRealmRoleConverter implements Converter<Jwt, Collection<GrantedAuthority>> {

    private static final String REALM_ACCESS = "realm_access";
    private static final String ROLES = "roles";
    private static final String ROLE_PREFIX = "ROLE_";

    // Extracts only "scope" attributes by default. Prefixes them with "SCOPE_"
    private final JwtGrantedAuthoritiesConverter scopeConverter = new JwtGrantedAuthoritiesConverter();

    @Override
    public Collection<GrantedAuthority> convert(@NonNull Jwt jwt) {

        Collection<GrantedAuthority> authorities = new ArrayList<>();

        Map<String, Object> realmAccess = jwt.getClaimAsMap(REALM_ACCESS);

        if(realmAccess != null && realmAccess.get(ROLES) instanceof Collection<?> roles) {
            roles.stream()
                    .map(String::valueOf) // Convert wildcard to string.
                    .map(role -> new SimpleGrantedAuthority(ROLE_PREFIX + role))
                    .forEach(authorities::add);
        }

        authorities.addAll(scopeConverter.convert(jwt)); // Keep SCOPE_email, SCOPE_profile as authority.

        return authorities;
    }
}
