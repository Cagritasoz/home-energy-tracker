package com.cagritasoz.user_service.admin;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.RequiredArgsConstructor;
import org.springframework.data.web.PagedModel;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/admin/users")
@PreAuthorize("hasRole('admin')")
@RequiredArgsConstructor
public class AdminUserController {

    private final AdminUserService adminUserService;

    // page is 0-based. The upper bound on size stops a client from asking for the whole table.
    // PagedModel gives a stable JSON shape ({"content": [...], "page": {...}}) instead of
    // serializing Spring's PageImpl directly.
    @GetMapping
    public ResponseEntity<PagedModel<AdminUserResponse>> listUsers(
            @RequestParam(defaultValue = "0") @Min(0) int page,
            @RequestParam(defaultValue = "20") @Min(1) @Max(100) int size) {

        PagedModel<AdminUserResponse> users = new PagedModel<>(adminUserService.listUsers(page, size));

        return ResponseEntity.ok(users);

    }

    @GetMapping("/{id}")
    public ResponseEntity<AdminUserResponse> getUser(@PathVariable UUID id) {

        AdminUserResponse user = adminUserService.getUser(id);

        return ResponseEntity.ok(user);

    }
}
