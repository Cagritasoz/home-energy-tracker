package com.cagritasoz.user_service.service;

import com.cagritasoz.user_service.dto.UserDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
public class UserService {


    public UserDto createUser(UserDto userDto) {
        // TODO: simulate user creation
        log.info("Creating user {}", userDto);
        return userDto;
    }
}

