package com.cagritasoz.user_service.controller;

import com.cagritasoz.user_service.dto.UserDto;
import com.cagritasoz.user_service.service.UserService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    @PostMapping
    public ResponseEntity<UserDto> createUser(@RequestBody UserDto userDto) {

        UserDto createdUser = userService.createUser(userDto);

        return ResponseEntity.status(HttpStatus.CREATED).body(createdUser);

    }

    @GetMapping("/{id}")
    public ResponseEntity<UserDto> getUserById(@PathVariable Long id) {

        final UserDto foundUser;

        foundUser = userService.getUserById(id);

        return ResponseEntity.ok(foundUser);

    }

    @PutMapping("/{id}")
    public ResponseEntity<UserDto> updateUser(@PathVariable Long id,
                                              @RequestBody UserDto userDto) {

        UserDto updatedUser = userService.updateUser(id, userDto);

        return ResponseEntity.ok(updatedUser);

    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteUser(@PathVariable Long id) {

        userService.deleteUser(id);

        return ResponseEntity.noContent().build();
    }
}
