package com.cagritasoz.user_service.testsupport.web;

import org.jspecify.annotations.NonNull;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import java.util.List;
import java.util.UUID;

public record Route(String label, HttpMethod method, String path, boolean customerApi, int successStatus) {

    public static final Route ME_GET = new Route("GET /users/me", HttpMethod.GET, "/api/v1/users/me", true, 200);

    public static final Route ME_PATCH = new Route("PATCH /users/me", HttpMethod.PATCH, "/api/v1/users/me", true, 200);

    public static final Route ME_DELETE = new Route("DELETE /users/me", HttpMethod.DELETE, "/api/v1/users/me", true, 202);

    public static final Route ME_CANCEL_DELETION = new Route("POST /users/me/deletion/cancel", HttpMethod.POST,
            "/api/v1/users/me/deletion/cancel", true, 204);

    public static final Route ADMIN_LIST = new Route("GET /admin/users", HttpMethod.GET, "/api/v1/admin/users", false, 200);

    public static final Route ADMIN_ONE = new Route("GET /admin/users/{id}", HttpMethod.GET,
            "/api/v1/admin/users/" + UUID.fromString("33333333-3333-3333-3333-333333333333"), false, 200);

    public static final Route ADMIN_UNMAPPED = new Route("GET /admin/unmapped", HttpMethod.GET, "/api/v1/admin/unmapped", false, 404);

    public static final Route UNKNOWN = new Route("GET /unknown", HttpMethod.GET, "/api/v1/unknown", false, 404);

    public static final List<Route> CUSTOMER = List.of(ME_GET, ME_PATCH, ME_DELETE, ME_CANCEL_DELETION);

    public static final List<Route> ADMIN = List.of(ADMIN_LIST, ADMIN_ONE, ADMIN_UNMAPPED);

    public static final List<Route> ALL = List.of(ME_GET, ME_PATCH, ME_DELETE, ME_CANCEL_DELETION, ADMIN_LIST, ADMIN_ONE, ADMIN_UNMAPPED, UNKNOWN);

    public MockHttpServletRequestBuilder request() {
        MockHttpServletRequestBuilder builder = MockMvcRequestBuilders.request(method, path);
        if (method == HttpMethod.PATCH) {
            builder.contentType(MediaType.APPLICATION_JSON).content("{}");
        }
        return builder;
    }

    @Override
    public @NonNull String toString() {
        return label;
    }
}
