package com.cagritasoz.user_service.security;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.json.ProblemDetailJacksonMixin;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.access.AccessDeniedException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNullPointerException;

class ProblemDetailAccessDeniedHandlerTest {

    // The mixin flattens ProblemDetail's extra properties (timestamp) to the top level, as Boot's mapper does in the running app.
    private final JsonMapper jsonMapper = JsonMapper.builder()
            .addMixIn(ProblemDetail.class, ProblemDetailJacksonMixin.class)
            .build();

    private final ProblemDetailAccessDeniedHandler handler = new ProblemDetailAccessDeniedHandler(jsonMapper);

    @Test
    void handle_answers403WithProblemDetailAndNoChallenge() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/admin/users");
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(request, response, new AccessDeniedException("Access Denied: needs ROLE_admin"));

        JsonNode body = jsonMapper.readTree(response.getContentAsString());

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentType()).startsWith(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        assertThat(response.getHeader(HttpHeaders.WWW_AUTHENTICATE)).isNull();
        assertThat(body.get("type").asString()).isEqualTo("https://home-energy-tracker/problems/forbidden");
        assertThat(body.get("title").asString()).isEqualTo("Forbidden");
        assertThat(body.get("status").asInt()).isEqualTo(403);
        assertThat(body.get("detail").asString()).isEqualTo("You do not have permission to perform this action.");
        assertThat(body.get("instance").asString()).isEqualTo("/api/v1/admin/users");
        assertThat(body.get("timestamp").asString()).isNotBlank();
    }

    @Test
    void handle_neverEchoesTheExceptionMessage() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();

        handler.handle(new MockHttpServletRequest("GET", "/x"), response, new AccessDeniedException("needs ROLE_admin"));

        assertThat(response.getContentAsString()).doesNotContain("ROLE_admin");
    }

    // Lombok generated @NonNull behavior test. All parameters are annotated with Lombok's @NonNull.
    @Test
    void handle_anyNullArgument_isRejectedWithANullPointerException() {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();
        AccessDeniedException denied = new AccessDeniedException("x");

        assertThatNullPointerException().isThrownBy(() -> handler.handle(null, response, denied));
        assertThatNullPointerException().isThrownBy(() -> handler.handle(request, null, denied));
        assertThatNullPointerException().isThrownBy(() -> handler.handle(request, response, null));
    }
}
