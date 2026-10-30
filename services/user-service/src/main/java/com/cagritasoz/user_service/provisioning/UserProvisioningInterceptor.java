package com.cagritasoz.user_service.provisioning;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;

@Component
@RequiredArgsConstructor
public class UserProvisioningInterceptor implements HandlerInterceptor {

    private static final String CUSTOMER_AUTHORITY = "ROLE_user";

    private final UserProvisioningService provisioningService;

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {

        // Only a customer is ever provisioned. This does not rely on the URL rules having rejected other roles first:
        // this interceptor runs before method security, so a token without the user role must be skipped here.
        if(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken authToken
                && authToken.getAuthorities().stream().anyMatch(a -> CUSTOMER_AUTHORITY.equals(a.getAuthority()))) {

            // TODO: Provisioning check for every single request for user API could be a bottleneck.
            provisioningService.ensureUsable(authToken.getToken());

        }

        return true; // Continue to the controller.

    }

}
