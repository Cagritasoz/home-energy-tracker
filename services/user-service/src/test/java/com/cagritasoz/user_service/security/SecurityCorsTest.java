package com.cagritasoz.user_service.security;

import com.cagritasoz.user_service.testsupport.TestTokens;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.http.HttpHeaders;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SecurityCorsTest extends SecuritySliceTest {

    private static final String ALLOWED_ORIGIN = "http://localhost:3000";

    private MockHttpServletRequestBuilder preflight(String path, String origin, String method, String headers) {
        MockHttpServletRequestBuilder request = options(path)
                .header(HttpHeaders.ORIGIN, origin)
                .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, method);
        if (headers != null) {
            request.header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, headers);
        }
        return request;
    }

    @ParameterizedTest
    @ValueSource(strings = {"/api/v1/users/me", "/api/v1/admin/users"})
    void preflightFromTheAllowedOrigin_isAnsweredWithoutAToken(String path) throws Exception {
        mockMvc.perform(preflight(path, ALLOWED_ORIGIN, "PATCH", "authorization,content-type"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_METHODS, "GET,POST,PATCH,DELETE,OPTIONS"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "authorization, content-type"))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_MAX_AGE, "3600"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
        verifyNoServiceWasCalled();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://evil.example",
            "http://localhost:3001",
            "https://localhost:3000",
            "http://localhost:3000.evil.example",
            "http://127.0.0.1:3000",
            "null"
    })
    void preflightFromAnyOtherOrigin_isRejected(String origin) throws Exception {
        mockMvc.perform(preflight("/api/v1/users/me", origin, "GET", null))
                .andExpect(status().isForbidden())
                .andExpect(content().string("Invalid CORS request"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
        verifyNoServiceWasCalled();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "TRACE", "HEAD"})
    void preflightForAMethodTheApiDoesNotAllow_isRejected(String method) throws Exception {
        mockMvc.perform(preflight("/api/v1/users/me", ALLOWED_ORIGIN, method, null))
                .andExpect(status().isForbidden())
                .andExpect(content().string("Invalid CORS request"));
    }

    @Test
    void preflightForAHeaderTheApiDoesNotAllow_doesNotGrantThatHeader() throws Exception {
        mockMvc.perform(preflight("/api/v1/users/me", ALLOWED_ORIGIN, "GET", "authorization,x-custom"))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_HEADERS, "authorization"));
    }

    @Test
    void actualRequestFromTheAllowedOrigin_carriesTheCorsHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN)
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(USER_ID).bearer()))
                .andExpect(status().isOk())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_EXPOSE_HEADERS, "Location"))
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_CREDENTIALS));
    }

    @Test
    void actualRequestFromAnotherOrigin_isRejectedEvenWithAValidToken() throws Exception {
        mockMvc.perform(get("/api/v1/users/me")
                        .header(HttpHeaders.ORIGIN, "http://evil.example")
                        .header(HttpHeaders.AUTHORIZATION, TestTokens.user(USER_ID).bearer()))
                .andExpect(status().isForbidden())
                .andExpect(content().string("Invalid CORS request"));
        verifyNoServiceWasCalled();
    }

    @Test
    void unauthorizedResponseToTheAllowedOrigin_stillCarriesTheCorsHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.ORIGIN, ALLOWED_ORIGIN))
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));
    }

    @Test
    void requestWithoutAnOriginHeader_getsNoCorsHeaders() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, TestTokens.user(USER_ID).bearer()))
                .andExpect(status().isOk())
                .andExpect(header().doesNotExist(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN));
    }

    @Test
    void allowedOrigin_isNeverAWildcard() throws Exception {
        mockMvc.perform(preflight("/api/v1/users/me", ALLOWED_ORIGIN, "GET", null))
                .andExpect(header().string(HttpHeaders.ACCESS_CONTROL_ALLOW_ORIGIN, ALLOWED_ORIGIN));
    }
}
