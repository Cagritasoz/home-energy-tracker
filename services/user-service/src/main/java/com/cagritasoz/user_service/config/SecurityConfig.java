package com.cagritasoz.user_service.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Resource-server wiring only - no endpoint-specific authorization rules yet, because no
 * endpoints exist yet (see CLAUDE.md's user-service status). Every request just has to carry a
 * JWT that validates against Keycloak's JWKS (spring.security.oauth2.resourceserver.jwt.issuer-uri
 * in application.properties); GET /users/me vs admin GET /users/{id} authorization comes later,
 * once those controllers exist.
 */
@Configuration
public class SecurityConfig {

    @Bean
    SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {
        http
            // CSRF protects cookie-authenticated browser sessions from a malicious page submitting
            // a form on the user's behalf. Every request here authenticates via a bearer token,
            // not a cookie, so there's no session to forge - leaving CSRF enabled would just
            // reject legitimate stateless API calls for no protective benefit.
            .csrf(csrf -> csrf.disable())
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .oauth2ResourceServer(oauth2 -> oauth2.jwt(jwt -> {}));
        return http.build();
    }
}
