package com.cagritasoz.user_service.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.authentication.AuthenticationServiceException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class TokenVerificationUnavailableFilterTest {

    private final JsonMapper jsonMapper = JsonMapper.builder()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();

    private final TokenVerificationUnavailableFilter filter = new TokenVerificationUnavailableFilter(jsonMapper);

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private final FilterChain chain = mock(FilterChain.class);

    @Test
    void doFilter_nothingGoesWrong_passesTheRequestOnUntouched() throws Exception {
        filter.doFilter(request, response, chain);

        verify(chain).doFilter(any(), any());
        assertThat(response.getStatus()).isEqualTo(200);
        assertThat(response.getContentAsString()).isEmpty();
    }

    @Test
    void doFilter_tokenCannotBeVerified_answers503WithAProblemDetail() throws Exception {
        doThrow(new AuthenticationServiceException("500 Internal Server Error on GET request for \"http://keys.internal/jwks\""))
                .when(chain).doFilter(any(), any());

        filter.doFilter(request, response, chain);

        JsonNode body = jsonMapper.readTree(response.getContentAsString());

        assertThat(response.getStatus()).isEqualTo(503);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(body.get("type").asString()).isEqualTo("https://home-energy-tracker/problems/service-unavailable");
        assertThat(body.get("title").asString()).isEqualTo("Service unavailable");
        assertThat(body.get("status").asInt()).isEqualTo(503);
        assertThat(body.get("detail").asString()).isEqualTo("Access tokens cannot be verified right now. Try again shortly.");
        assertThat(body.get("instance").asString()).isEqualTo("/api/v1/users/me");
        assertThat(body.get("timestamp").asString()).isNotBlank();
    }

    @Test
    void doFilter_tokenCannotBeVerified_neverRevealsTheCause() throws Exception {
        doThrow(new AuthenticationServiceException("500 Internal Server Error on GET request for \"http://keys.internal/jwks\""))
                .when(chain).doFilter(any(), any());

        filter.doFilter(request, response, chain);

        assertThat(response.getContentAsString()).doesNotContain("keys.internal").doesNotContain("500 Internal");
    }

    @Test
    void doFilter_anyOtherException_isNotSwallowed() throws Exception {
        AccessDeniedException denied = new AccessDeniedException("no lmao");
        doThrow(denied).when(chain).doFilter(any(), any());

        assertThatThrownBy(() -> filter.doFilter(request, response, chain)).isSameAs(denied);
    }

    @Test
    void doFilter_ioFailureFromTheChain_isNotSwallowed() throws Exception {
        IOException failure = new IOException("broken pipe");
        doThrow(failure).when(chain).doFilter(any(), any());

        assertThatThrownBy(() -> filter.doFilter(request, response, chain)).isSameAs(failure);
    }

    @Test
    void doFilter_servletFailureFromTheChain_isNotSwallowed() throws Exception {
        ServletException failure = new ServletException("boom");
        doThrow(failure).when(chain).doFilter(any(), any());

        assertThatThrownBy(() -> filter.doFilter(request, response, chain)).isSameAs(failure);
    }
}
