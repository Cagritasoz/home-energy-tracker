package com.cagritasoz.user_service.security;

import com.cagritasoz.user_service.dto.UserAdminResponse;
import com.cagritasoz.user_service.dto.UserResponse;
import com.cagritasoz.user_service.service.UserAdminService;
import com.cagritasoz.user_service.service.UserDeletionService;
import com.cagritasoz.user_service.service.UserProvisioningService;
import com.cagritasoz.user_service.service.UserService;
import com.cagritasoz.user_service.testsupport.LocalJwks;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@SecuritySlice
public abstract class SecuritySliceTest {

    protected static final UUID USER_ID = UUID.fromString("11111111-1111-1111-1111-111111111111");

    protected static final UUID ADMIN_ID = UUID.fromString("22222222-2222-2222-2222-222222222222");

    protected static final String JWK_SET_URI_PROPERTY = "spring.security.oauth2.resourceserver.jwt.jwk-set-uri";

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected UserService userService;

    @Autowired
    protected UserDeletionService userDeletionService;

    @Autowired
    protected UserAdminService userAdminService;

    @Autowired
    protected UserProvisioningService provisioningService;

    @DynamicPropertySource
    static void sharedJwks(DynamicPropertyRegistry registry) {
        registry.add(JWK_SET_URI_PROPERTY, LocalJwks.SHARED::url);
    }

    @BeforeEach
    void stubServices() {
        when(userService.getUser(any())).thenReturn(UserResponse.builder().build());
        when(userService.updateUser(any(), any())).thenReturn(UserResponse.builder().build());
        when(userDeletionService.scheduleDeletion(any())).thenReturn(UserResponse.builder().build());
        when(userAdminService.getUser(any())).thenReturn(UserAdminResponse.builder().build());
        when(userAdminService.listUsers(anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of()));
    }

    protected ResultActions call(Route route, String authorization) throws Exception {
        MockHttpServletRequestBuilder request = route.request();
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mockMvc.perform(request);
    }

    protected void verifyNoServiceWasCalled() {
        verifyNoInteractions(userService, userDeletionService, userAdminService, provisioningService);
    }
}
