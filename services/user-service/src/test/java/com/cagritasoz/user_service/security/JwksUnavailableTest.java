package com.cagritasoz.user_service.security;

import com.cagritasoz.user_service.testsupport.LocalJwks;
import com.cagritasoz.user_service.testsupport.TestTokens;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SecuritySlice

// DirtiesContext BEFORE_EACH_TEST_METHOD, new context before each test.
// JWT decoder holds the downloaded keys, the aim is to rebuild the bean to get a brand-new cache.
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_EACH_TEST_METHOD)
class JwksUnavailableTest {

    // Has its own instance, static so that it keeps running across new context and keeps state.
    // jwks and healthyEndpoint methods ensure that server is put back into a normal state.
    private static final LocalJwks JWKS = new LocalJwks(LocalJwks.SIGNING_KEY);

    // Called everytime spring builds context.
    // Since before each test runs the context is rebuilt, jwks method is called everytime too.
    @DynamicPropertySource
    static void jwks(DynamicPropertyRegistry registry) {
        JWKS.resetRequestCount();
        registry.add(SecuritySliceTest.JWK_SET_URI_PROPERTY, JWKS::url);
    }

    @Autowired
    private MockMvc mockMvc;

    @BeforeEach
    void healthyEndpoint() {
        JWKS.recover();
        JWKS.publish(LocalJwks.SIGNING_KEY);
    }

    private ResultActions callWithValidToken() throws Exception {
        return mockMvc.perform(get("/api/v1/users/me")
                .header(HttpHeaders.AUTHORIZATION, TestTokens.user(UUID.randomUUID()).bearer()));
    }

    // First request with token makes a call.
    @Test
    void startingTheContext_makesNoCallToTheKeyEndpoint() {
        assertThat(JWKS.requestCount()).isZero();
    }

    @Test
    void firstTokenAfterStartup_fetchesTheKeysAndIsAccepted() throws Exception {
        int before = JWKS.requestCount();

        callWithValidToken().andExpect(status().isOk());

        assertThat(JWKS.requestCount()).isEqualTo(before + 1);
    }

    private void assertServiceUnavailable(ResultActions result) throws Exception {
        result.andExpect(status().isServiceUnavailable())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(header().doesNotExist(HttpHeaders.WWW_AUTHENTICATE))
                .andExpect(jsonPath("$.type").value("https://home-energy-tracker/problems/service-unavailable"))
                .andExpect(jsonPath("$.title").value("Service unavailable"))
                .andExpect(jsonPath("$.status").value(503))
                .andExpect(jsonPath("$.detail").value("Access tokens cannot be verified right now. Try again shortly."))
                .andExpect(jsonPath("$.instance").value("/api/v1/users/me"))
                .andExpect(jsonPath("$.timestamp").isNotEmpty());
    }

    @Test
    void keyEndpointAnswering500_answers503() throws Exception {
        JWKS.failWith(500);

        assertServiceUnavailable(callWithValidToken());
    }

    @Test
    void keyEndpointAnswering404_answers503() throws Exception {
        JWKS.failWith(404);

        assertServiceUnavailable(callWithValidToken());
    }

    @Test
    void keyEndpointAnsweringGarbage_answers503() throws Exception {
        JWKS.publishRaw("this is not json");

        assertServiceUnavailable(callWithValidToken());
    }

    @Test
    void unavailableResponse_doesNotRevealTheKeyEndpointAddress() throws Exception {
        JWKS.failWith(500);

        String body = callWithValidToken().andReturn().getResponse().getContentAsString();

        assertThat(body).doesNotContain(JWKS.url()).doesNotContain("127.0.0.1");
    }

    @Test
    void keyEndpointAnsweringAnEmptyKeySet_rejectsTheTokenAsInvalid() throws Exception {
        JWKS.publishRaw("{\"keys\":[]}");

        callWithValidToken()
                .andExpect(status().isUnauthorized())
                .andExpect(header().string(HttpHeaders.WWW_AUTHENTICATE,
                        "Bearer realm=\"energy-tracker\", error=\"invalid_token\""));
    }

    @Test
    void requestWithoutAToken_doesNotNeedTheKeyEndpointAtAll() throws Exception {
        JWKS.failWith(500);

        mockMvc.perform(get("/api/v1/users/me")).andExpect(status().isUnauthorized());

        assertThat(JWKS.requestCount()).isZero();
    }

    @Test
    void afterTheKeyEndpointRecovers_theSameRequestSucceedsWithoutARestart() throws Exception {

        JWKS.failWith(500);

        // No cached keys.
        assertServiceUnavailable(callWithValidToken());

        // Service is back.
        JWKS.recover();

        callWithValidToken().andExpect(status().isOk());
    }
}
