package com.cagritasoz.user_service.security;

import com.cagritasoz.user_service.testsupport.LocalJwks;
import com.cagritasoz.user_service.testsupport.TestTokens;
import com.nimbusds.jose.jwk.RSAKey;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SecuritySlice
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class JwksKeyRotationTest {

    private static final RSAKey KEY_ONE = LocalJwks.generate("rotation-key-1");

    private static final RSAKey KEY_TWO = LocalJwks.generate("rotation-key-2");

    private static final RSAKey KEY_THREE = LocalJwks.generate("rotation-key-3");

    // Has its own instance.
    private static final LocalJwks JWKS = new LocalJwks(KEY_ONE);

    // @DynamicPropertySource is the connection between the test and the fake key server.
    // Overrides JWK_SET_URI_PROPERTY in application.yaml to the fake key servers url.
    // It has to be dynamic because the fake server picks a random free port when it starts.
    // The address doesn't exist until runtime, so it can't be written in a file or an annotation.
    // The address can not be known in advance.
    @DynamicPropertySource
    static void jwks(DynamicPropertyRegistry registry) {
        registry.add(SecuritySliceTest.JWK_SET_URI_PROPERTY, JWKS::url);
    }

    @Autowired
    private MockMvc mockMvc;

    private ResultActions callSignedWith(RSAKey key) throws Exception {
        String token = TestTokens.user(UUID.randomUUID()).signedWith(key).kid(key.getKeyID()).signed();
        return mockMvc.perform(get("/api/v1/users/me").header(HttpHeaders.AUTHORIZATION, "Bearer " + token));
    }

    @Test
    @Order(1)
    void keysAreFetchedOnTheFirstTokenOnly() throws Exception {
        assertThat(JWKS.requestCount()).isZero();

        callSignedWith(KEY_ONE).andExpect(status().isOk());

        assertThat(JWKS.requestCount()).isEqualTo(1);
    }

    @Test
    @Order(2)
    void laterRequestsReuseTheCachedKeys() throws Exception {
        for (int i = 0; i < 5; i++) {
            callSignedWith(KEY_ONE).andExpect(status().isOk());
        }

        assertThat(JWKS.requestCount()).isEqualTo(1); // Requests to local jwks server is still one.
    }

    @Test
    @Order(3)
    void unknownKeyId_triggersARefetchAndTheNewKeyIsThenAccepted() throws Exception {
        JWKS.publish(KEY_ONE, KEY_TWO);

        callSignedWith(KEY_TWO).andExpect(status().isOk()); // KEY_TWO is a cache miss, refetch from JWK_SET_URI_PROPERTY. Which corresponds to JWKS url.

        assertThat(JWKS.requestCount()).isEqualTo(2);
    }

    @Test
    @Order(4)
    void cachedKeysKeepValidatingWhileTheEndpointIsDown() throws Exception {
        JWKS.failWith(500);
        int before = JWKS.requestCount();

        callSignedWith(KEY_ONE).andExpect(status().isOk());
        callSignedWith(KEY_TWO).andExpect(status().isOk());

        assertThat(JWKS.requestCount()).isEqualTo(before);
    }

    @Test
    @Order(5)
    void unknownKeyIdWhileTheEndpointIsDown_answers503() throws Exception {
        callSignedWith(KEY_THREE).andExpect(status().isServiceUnavailable());
    }

    @Test
    @Order(6)
    void keyThatTheEndpointNeverPublished_isRejectedAfterRecovery() throws Exception {
        JWKS.recover(); // Service is back.

        callSignedWith(KEY_THREE).andExpect(status().isUnauthorized()); // KEY_THREE not in the set and never published.
    }
}
