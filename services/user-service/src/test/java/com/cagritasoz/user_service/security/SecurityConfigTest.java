package com.cagritasoz.user_service.security;

import com.cagritasoz.user_service.testsupport.config.ConfigFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.web.cors.CorsConfiguration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// What SecurityConfig takes from the settings. How the chain behaves with them is in the security tests, which run the
// real configuration.
class SecurityConfigTest {

    private static SecurityConfig config(SecurityProperties security, OAuth2ResourceServerProperties resourceServer) {

        return new SecurityConfig(null, null, null, security, resourceServer);

    }

    private static OAuth2ResourceServerProperties resourceServer(String jwkSetUri, String issuerUri) {

        OAuth2ResourceServerProperties properties = new OAuth2ResourceServerProperties();
        properties.getJwt().setJwkSetUri(jwkSetUri);
        properties.getJwt().setIssuerUri(issuerUri);

        return properties;

    }

    @Test
    void corsConfigurationSource_allowsTheConfiguredOriginsAndNoOthers() {

        SecurityConfig config = config(
                ConfigFixtures.security("cors.allowed-origins=https://app.example.com,https://admin.example.com"),
                new OAuth2ResourceServerProperties());

        CorsConfiguration cors = config.corsConfigurationSource()
                .getCorsConfiguration(new MockHttpServletRequest("GET", "/api/v1/users/me"));

        assertThat(cors).isNotNull();
        assertThat(cors.getAllowedOrigins()).containsExactly("https://app.example.com", "https://admin.example.com");

    }

    @Test
    void jwtDecoder_withBothAddresses_buildsWithoutCallingKeycloak() {

        SecurityConfig config = config(ConfigFixtures.security(), resourceServer("http://localhost:1/certs", "http://localhost:1/realm"));

        assertThat(config.jwtDecoder()).isNotNull();

    }

    @Test
    void jwtDecoder_withoutTheKeySetAddress_refusesToBuildAndNamesTheSetting() {

        SecurityConfig config = config(ConfigFixtures.security(), resourceServer(null, "http://localhost:1/realm"));

        assertThatThrownBy(config::jwtDecoder)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spring.security.oauth2.resourceserver.jwt.jwk-set-uri");

    }

    @Test
    void jwtDecoder_withoutTheIssuer_refusesToBuildAndNamesTheSetting() {

        SecurityConfig config = config(ConfigFixtures.security(), resourceServer("http://localhost:1/certs", " "));

        assertThatThrownBy(config::jwtDecoder)
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("spring.security.oauth2.resourceserver.jwt.issuer-uri");

    }
}
