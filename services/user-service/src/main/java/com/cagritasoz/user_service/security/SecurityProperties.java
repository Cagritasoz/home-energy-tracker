package com.cagritasoz.user_service.security;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.util.List;

// app.security.* (explained in application.yaml). The signing keys' address and the issuer are Spring Boot's own
// spring.security.oauth2.resourceserver.jwt.*, read in SecurityConfig through OAuth2ResourceServerProperties.
@Validated
@ConfigurationProperties(prefix = "app.security", ignoreUnknownFields = false)
public record SecurityProperties(@NotBlank String requiredAudience, @NotNull @Valid Cors cors) {

    public record Cors(@NotEmpty List<@NotBlank String> allowedOrigins) {
    }
}
