package com.cagritasoz.user_service.security;

import com.cagritasoz.user_service.config.security.KeycloakRealmRoleConverter;
import com.cagritasoz.user_service.config.security.ProblemDetailAccessDeniedHandler;
import com.cagritasoz.user_service.config.security.ProblemDetailAuthenticationEntryPoint;
import com.cagritasoz.user_service.config.security.SecurityConfig;
import com.cagritasoz.user_service.config.web.WebMvcConfig;
import com.cagritasoz.user_service.controller.UserAdminController;
import com.cagritasoz.user_service.controller.UserController;
import com.cagritasoz.user_service.exception.GlobalExceptionHandler;
import com.cagritasoz.user_service.interceptor.UserProvisioningInterceptor;
import com.cagritasoz.user_service.service.UserAdminService;
import com.cagritasoz.user_service.service.UserProvisioningService;
import com.cagritasoz.user_service.service.UserService;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.TYPE)
@WebMvcTest(controllers = {UserController.class, UserAdminController.class})
@Import({
        SecurityConfig.class,
        ProblemDetailAuthenticationEntryPoint.class,
        ProblemDetailAccessDeniedHandler.class,
        KeycloakRealmRoleConverter.class,
        WebMvcConfig.class,
        UserProvisioningInterceptor.class,
        GlobalExceptionHandler.class
})
@MockitoBean(types = {UserService.class, UserAdminService.class, UserProvisioningService.class})
public @interface SecuritySlice {
}
