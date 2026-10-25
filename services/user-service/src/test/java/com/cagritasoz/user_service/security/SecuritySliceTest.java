package com.cagritasoz.user_service.security;

import com.cagritasoz.user_service.admin.AdminUserResponse;
import com.cagritasoz.user_service.admin.AdminUserService;
import com.cagritasoz.user_service.deletion.DeletionSchedulingService;
import com.cagritasoz.user_service.profile.UserProfileService;
import com.cagritasoz.user_service.provisioning.UserProvisioningService;
import com.cagritasoz.user_service.testsupport.jwt.LocalJwks;
import com.cagritasoz.user_service.user.UserResponse;
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

    @Autowired
    protected MockMvc mockMvc;

    @Autowired
    protected UserProfileService userProfileService;

    @Autowired
    protected DeletionSchedulingService deletionSchedulingService;

    @Autowired
    protected AdminUserService adminUserService;

    @Autowired
    protected UserProvisioningService provisioningService;

    @DynamicPropertySource
    static void sharedJwks(DynamicPropertyRegistry registry) {
        registry.add(LocalJwks.JWK_SET_URI_PROPERTY, LocalJwks.SHARED::url);
    }

    @BeforeEach
    void stubServices() {
        when(userProfileService.getUser(any())).thenReturn(UserResponse.builder().build());
        when(userProfileService.updateUser(any(), any())).thenReturn(UserResponse.builder().build());
        when(deletionSchedulingService.scheduleDeletion(any())).thenReturn(UserResponse.builder().build());
        when(adminUserService.getUser(any())).thenReturn(AdminUserResponse.builder().build());
        when(adminUserService.listUsers(anyInt(), anyInt())).thenReturn(new PageImpl<>(List.of()));
    }

    protected ResultActions call(Route route, String authorization) throws Exception {
        MockHttpServletRequestBuilder request = route.request();
        if (authorization != null) {
            request.header(HttpHeaders.AUTHORIZATION, authorization);
        }
        return mockMvc.perform(request);
    }

    protected void verifyNoServiceWasCalled() {
        verifyNoInteractions(userProfileService, deletionSchedulingService, adminUserService, provisioningService);
    }
}
