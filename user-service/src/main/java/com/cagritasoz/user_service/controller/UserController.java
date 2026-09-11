package com.cagritasoz.user_service.controller;

import com.cagritasoz.user_service.dto.UserRequest;
import com.cagritasoz.user_service.dto.UserResponse;
import com.cagritasoz.user_service.service.UserService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    public ResponseEntity<UserResponse> createUser(@Valid @RequestBody UserRequest userRequest) {

        UserResponse createdUser = userService.createUser(userRequest);

        return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);

    }

    @GetMapping
    public ResponseEntity<List<UserResponse>> getUsers() {

        List<UserResponse> foundUsers = userService.getUsers();

        return ResponseEntity.ok(foundUsers);

    }

    @GetMapping("/{id}")
    public ResponseEntity<UserResponse> getUserById(@PathVariable Long id) {

        UserResponse foundUser = userService.getUserById(id);

        return ResponseEntity.ok(foundUser);

    }

    @PutMapping("/{id}")
    public ResponseEntity<UserResponse> updateUser(@PathVariable Long id,
                                                    @Valid @RequestBody UserRequest userRequest) {

        UserResponse updatedUser = userService.updateUser(id, userRequest);

        return ResponseEntity.ok(updatedUser);

    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {

        userService.deleteUser(id);

        return ResponseEntity.noContent().build();
    }
}
