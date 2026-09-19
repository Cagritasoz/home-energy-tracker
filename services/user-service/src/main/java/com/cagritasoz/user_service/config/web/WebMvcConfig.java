package com.cagritasoz.user_service.config.web;


import com.cagritasoz.user_service.interceptor.UserProvisioningInterceptor;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    private final UserProvisioningInterceptor provisioningInterceptor;

    @Override
    public void addInterceptors(@NonNull InterceptorRegistry registry) {

        registry.addInterceptor(provisioningInterceptor)
                .addPathPatterns("/api/**"); // Apply to all endpoints.

    }
}