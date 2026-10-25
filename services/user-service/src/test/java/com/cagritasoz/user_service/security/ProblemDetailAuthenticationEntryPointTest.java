package com.cagritasoz.user_service.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ProblemDetailAuthenticationEntryPointTest {

    private final JsonMapper jsonMapper = JsonMapper.builder()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();

    private final ProblemDetailAuthenticationEntryPoint entryPoint = new ProblemDetailAuthenticationEntryPoint(jsonMapper);

    private final MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/users/me");

    private final MockHttpServletResponse response = new MockHttpServletResponse();

    private JsonNode body() throws Exception {
        return jsonMapper.readTree(response.getContentAsString());
    }

    @Test
    void commence_missingToken_answers401WithPlainBearerChallenge() throws Exception {
        entryPoint.commence(request, response, new BadCredentialsException("Full authentication is required"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isEqualTo("Bearer realm=\"energy-tracker\"");
        assertThat(body().get("detail").asString()).isEqualTo("Authentication is required. Provide a valid bearer token.");
    }

    @Test
    void commence_invalidToken_answers401WithInvalidTokenChallenge() throws Exception {
        entryPoint.commence(request, response, new InvalidBearerTokenException("Jwt expired at 2026-01-01T00:00:00Z"));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer realm=\"energy-tracker\", error=\"invalid_token\"");
        assertThat(body().get("detail").asString())
                .isEqualTo("The access token is expired, malformed, or not valid for this service.");
    }

    @Test
    void commence_malformedBearerHeader_answers401WithInvalidTokenChallenge() throws Exception {
        entryPoint.commence(request, response,
                new OAuth2AuthenticationException(BearerTokenErrors.invalidToken("Bearer token is malformed")));

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE))
                .isEqualTo("Bearer realm=\"energy-tracker\", error=\"invalid_token\"");
    }

    @Test
    void commence_writesProblemDetailWithTheStandardFields() throws Exception {
        entryPoint.commence(request, response, new BadCredentialsException("x"));

        JsonNode body = body();

        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(body.get("type").asString()).isEqualTo("https://home-energy-tracker/problems/unauthorized");
        assertThat(body.get("title").asString()).isEqualTo("Unauthorized");
        assertThat(body.get("status").asInt()).isEqualTo(401);
        assertThat(body.get("instance").asString()).isEqualTo("/api/v1/users/me");
        assertThat(body.get("timestamp").asString()).isNotBlank();
    }

    @Test
    void commence_neverEchoesTheExceptionMessage() throws Exception {
        entryPoint.commence(request, response, new InvalidBearerTokenException("secret-reason-abc123"));

        assertThat(response.getContentAsString()).doesNotContain("secret-reason-abc123");
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).doesNotContain("secret-reason-abc123");
    }

    @Test
    void commence_anyNullArgument_isRejectedWithANullPointerException() {
        BadCredentialsException failure = new BadCredentialsException("x");

        assertThatNullPointerException().isThrownBy(() -> entryPoint.commence(null, response, failure));
        assertThatNullPointerException().isThrownBy(() -> entryPoint.commence(request, null, failure));
        assertThatNullPointerException().isThrownBy(() -> entryPoint.commence(request, response, null));
    }
}
