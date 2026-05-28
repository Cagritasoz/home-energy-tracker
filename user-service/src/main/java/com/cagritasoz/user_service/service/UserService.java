package com.cagritasoz.user_service.service;

import com.cagritasoz.user_service.dto.UserDto;
import com.cagritasoz.user_service.entity.User;
import com.cagritasoz.user_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;


    public UserDto createUser(UserDto inputUser) {
        log.info("Creating user {}", inputUser);

        final User user = User.builder()
                .firstName(inputUser.getFirstName())
                .lastName(inputUser.getLastName())
                .email(inputUser.getEmail())
                .address(inputUser.getAddress())
                .alertsEnabled(inputUser.isAlertsEnabled())
                .energyAlertingThreshold(inputUser.getEnergyAlertingThreshold())
                .build();

        final User saved = userRepository.save(user); // id populated.

        return toDto(user);
    }

    private UserDto toDto(User user) {
        return UserDto.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .address(user.getAddress())
                .alertsEnabled(user.getAlertsEnabled())
                .energyAlertingThreshold(user.getEnergyAlertingThreshold())
                .build();
    }
}

