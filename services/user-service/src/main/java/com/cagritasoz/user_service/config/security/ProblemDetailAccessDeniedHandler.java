package com.cagritasoz.user_service.config.security;

import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

@Component
@RequiredArgsConstructor
public class ProblemDetailAccessDeniedHandler implements AccessDeniedHandler {

    private final ObjectMapper objectMapper;

    @Override
    public void handle(@NonNull HttpServletRequest request,
                       @NonNull HttpServletResponse response,
                       @NonNull AccessDeniedException accessDeniedException) throws IOException, ServletException {


        ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.FORBIDDEN);

        problemDetail.setType(URI.create("https://home-energy-tracker/problems/forbidden"));

        problemDetail.setTitle("Forbidden");

        problemDetail.setDetail("You do not have permission to perform this action.");

        problemDetail.setInstance(URI.create(request.getRequestURI()));

        problemDetail.setProperty("timestamp", Instant.now().toString());

        response.setStatus(HttpStatus.FORBIDDEN.value());

        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

        objectMapper.writeValue(response.getOutputStream(), problemDetail);
    }
}