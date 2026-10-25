package com.cagritasoz.user_service.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class ProblemDetailAuthenticationEntryPoint implements AuthenticationEntryPoint {

    private final ObjectMapper objectMapper;

    @Override
    public void commence(@NonNull HttpServletRequest request,
                         @NonNull HttpServletResponse response,
                         @NonNull AuthenticationException authException) throws IOException, ServletException {

        ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.UNAUTHORIZED);

        problemDetail.setType(URI.create("https://home-energy-tracker/problems/unauthorized"));

        problemDetail.setTitle("Unauthorized");

        boolean invalidToken = authException instanceof OAuth2AuthenticationException;

        problemDetail.setDetail(invalidToken
                // BearerTokenAuthenticationFilter validation failed should get this detail.
                ? "The access token is expired, malformed, or not valid for this service."
                // Missing token should get this detail.
                : "Authentication is required. Provide a valid bearer token.");

        problemDetail.setInstance(URI.create(request.getRequestURI()));

        problemDetail.setProperty("timestamp", Instant.now().toString());

        response.addHeader(HttpHeaders.WWW_AUTHENTICATE, invalidToken
                ? "Bearer realm=\"energy-tracker\", error=\"invalid_token\""
                : "Bearer realm=\"energy-tracker\"");

        response.setStatus(HttpStatus.UNAUTHORIZED.value());

        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        objectMapper.writeValue(response.getOutputStream(), problemDetail);
    }
}
