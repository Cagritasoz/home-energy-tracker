package com.cagritasoz.user_service.security;

import com.cagritasoz.user_service.admin.AdminUserController;
import com.cagritasoz.user_service.admin.AdminUserService;
import com.cagritasoz.user_service.deletion.AccountDeletionController;
import com.cagritasoz.user_service.deletion.DeletionSchedulingService;
import com.cagritasoz.user_service.profile.UserProfileController;
import com.cagritasoz.user_service.profile.UserProfileService;
import com.cagritasoz.user_service.provisioning.UserProvisioningInterceptor;
import com.cagritasoz.user_service.provisioning.UserProvisioningService;
import com.cagritasoz.user_service.provisioning.WebMvcConfig;
import com.cagritasoz.user_service.web.GlobalExceptionHandler;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@WebMvcTest(controllers = {UserProfileController.class, AccountDeletionController.class, AdminUserController.class})
@Import({
        SecurityConfig.class,
        ProblemDetailAuthenticationEntryPoint.class,
        ProblemDetailAccessDeniedHandler.class,
        KeycloakRealmRoleConverter.class,
        WebMvcConfig.class,
        UserProvisioningInterceptor.class,
        GlobalExceptionHandler.class
})
@MockitoBean(types = {UserProfileService.class, DeletionSchedulingService.class, AdminUserService.class, UserProvisioningService.class})
public @interface SecuritySlice {
}
