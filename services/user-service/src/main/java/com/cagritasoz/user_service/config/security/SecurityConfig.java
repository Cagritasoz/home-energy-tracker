package com.cagritasoz.user_service.config.security;

import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.annotation.web.configurers.RequestCacheConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.*;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationConverter;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.time.Duration;
import java.util.List;

@Configuration
@EnableWebSecurity
@EnableMethodSecurity
@RequiredArgsConstructor
public class SecurityConfig {

    private final KeycloakRealmRoleConverter roleConverter;

    private final ProblemDetailAuthenticationEntryPoint authenticationEntryPoint;

    private final ProblemDetailAccessDeniedHandler accessDeniedHandler;

    @Value("${spring.security.oauth2.resourceserver.jwt.issuer-uri}")
    private String issuerUri;

    @Value("${app.security.required-audience}")
    private String requiredAudience;


    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http) throws Exception {

        http
                .csrf(AbstractHttpConfigurer::disable)

                .cors(Customizer.withDefaults())

                .sessionManagement(session -> session
                        .sessionCreationPolicy(SessionCreationPolicy.STATELESS))

                .logout(AbstractHttpConfigurer::disable)

                .requestCache(RequestCacheConfigurer::disable)

                .authorizeHttpRequests(auth -> auth

                        // Infrastructure (Docker healthchecks, the Layer 4 test suite) calls this
                        // without a token. Deliberately just /actuator/health, not /actuator/** -
                        // only "health" is exposed over HTTP at all (see application.properties),
                        // and nothing under /actuator should ever be open-ended public.
                        .requestMatchers("/actuator/health").permitAll()

                        .requestMatchers("/api/v1/users/me").authenticated()

                        .requestMatchers("/api/v1/admin/users/**").hasRole("ADMIN")

                        .anyRequest().authenticated()) // Every other request still needs authentication.

                .oauth2ResourceServer(oauth2 -> oauth2
                        .jwt(jwt -> jwt
                                .decoder(jwtDecoder())
                                .jwtAuthenticationConverter(jwtAuthenticationConverter()))
                        .authenticationEntryPoint(authenticationEntryPoint)) // Handle jwt related validation exceptions.

                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(authenticationEntryPoint) // Handles missing token case.
                        .accessDeniedHandler(accessDeniedHandler));


        return http.build();
    }

    @Bean
    public JwtDecoder jwtDecoder() {

        NimbusJwtDecoder decoder = JwtDecoders.fromIssuerLocation(issuerUri);

        OAuth2TokenValidator<Jwt> defaults = JwtValidators.createDefaultWithIssuer(issuerUri);

        // Clock skew of 50 seconds instead of the default 60 seconds.
        // OAuth2TokenValidator<Jwt> timestampValidator = new JwtTimestampValidator(Duration.ofSeconds(50));

        OAuth2TokenValidator<Jwt> audienceValidator = new AudienceValidator(requiredAudience);

        // Plug in the custom audience validator with default validators.
        decoder.setJwtValidator(
                new DelegatingOAuth2TokenValidator<>(defaults, audienceValidator));

        return decoder;

    }

    @Bean
    public JwtAuthenticationConverter jwtAuthenticationConverter() {

        JwtAuthenticationConverter converter = new JwtAuthenticationConverter();

        // Plug in custom keycloak realm role converter in.
        converter.setJwtGrantedAuthoritiesConverter(roleConverter);

        converter.setPrincipalClaimName(JwtClaimNames.SUB);

        return converter;
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {

        CorsConfiguration config = new CorsConfiguration();

        config.setAllowedOrigins(List.of("http://localhost:3000")); // React frontend (even though it does not exist lol)

        config.setAllowedMethods(List.of("GET", "POST", "PATCH", "DELETE", "OPTIONS"));

        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));

        config.setExposedHeaders(List.of("Location"));

        config.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();

        source.registerCorsConfiguration("/**", config);

        return source;
    }
}