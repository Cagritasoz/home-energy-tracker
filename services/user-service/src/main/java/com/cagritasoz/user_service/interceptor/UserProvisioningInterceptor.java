package com.cagritasoz.user_service.interceptor;

import com.cagritasoz.user_service.service.UserProvisioningService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
@Slf4j
public class UserProvisioningInterceptor implements HandlerInterceptor {

    private final UserProvisioningService provisioningService;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {

        // Execution up to this point guarantees that this check is always true.
        if(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken authToken) {

            provisioningService.ensureUsable(authToken.getToken()); // Returns Jwt object.

            Jwt token = (Jwt) authToken.getPrincipal();

            log.info("Jwt claim map: [{}]", token.getClaims());

        }

        return true; // Continue to the controller.

    }

}