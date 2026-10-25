package com.cagritasoz.user_service.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.NotNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.security.authentication.AuthenticationServiceException;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.time.Instant;

// Spring Security rethrows an AuthenticationServiceException from the bearer token filter instead of handing it to the
// authentication entry point. It means the token could not be checked at all (the key endpoint is down, answers an error or
// answers garbage), which is not the client's fault: it must be a 503, not a 401 and not an unhandled 500. This filter sits
// right in front of the bearer token filter and turns that one exception into a problem detail. Not a bean on purpose:
// Spring Boot would register every Filter bean a second time in the servlet container.
@Slf4j
@RequiredArgsConstructor
public class TokenVerificationUnavailableFilter extends OncePerRequestFilter {

    private final ObjectMapper objectMapper;

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NotNull FilterChain chain)
            throws ServletException, IOException {

        try {

            chain.doFilter(request, response);

        } catch (AuthenticationServiceException e) {

            // Message only, no stack trace: while the key endpoint is down this would otherwise be one stack trace per request.
            log.warn("Access tokens cannot be verified right now: {}", e.getMessage());

            ProblemDetail problemDetail = ProblemDetail.forStatus(HttpStatus.SERVICE_UNAVAILABLE);

            problemDetail.setType(URI.create("https://home-energy-tracker/problems/service-unavailable"));

            problemDetail.setTitle("Service unavailable");

            problemDetail.setDetail("Access tokens cannot be verified right now. Try again shortly.");

            problemDetail.setInstance(URI.create(request.getRequestURI()));

            problemDetail.setProperty("timestamp", Instant.now().toString());

            response.setStatus(HttpStatus.SERVICE_UNAVAILABLE.value()); // 503

            response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);

            objectMapper.writeValue(response.getOutputStream(), problemDetail);
        }
    }
}
