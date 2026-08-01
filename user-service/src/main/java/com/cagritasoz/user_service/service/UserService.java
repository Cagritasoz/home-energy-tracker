package com.cagritasoz.user_service.service;

import com.cagritasoz.user_service.dto.UserDto;
import com.cagritasoz.user_service.entity.User;
import com.cagritasoz.user_service.exception.UserNotFoundException;
import com.cagritasoz.user_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    // TODO: Check already existing emails then save.
    public UserDto createUser(UserDto inputUser) {

        final User user = User.builder()
                .firstName(inputUser.getFirstName())
                .lastName(inputUser.getLastName())
                .email(inputUser.getEmail())
                .address(inputUser.getAddress())
                .alertsEnabled(inputUser.isAlertsEnabled())
                .energyAlertingThreshold(inputUser.getEnergyAlertingThreshold())
                .build();

        final User saved = userRepository.save(user); // id populated.

        return toDto(saved);
    }

    public UserDto getUserById(Long id) {

        User foundUser = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found!"));
        return toDto(foundUser);
    }

    public UserDto updateUser(Long id, UserDto userDto) {

        User foundUser = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found!")); // Found user is managed

        foundUser.setFirstName(userDto.getFirstName());
        foundUser.setLastName(userDto.getLastName());
        foundUser.setEmail(userDto.getEmail());
        foundUser.setAddress(userDto.getAddress());
        foundUser.setAlertsEnabled(userDto.isAlertsEnabled());
        foundUser.setEnergyAlertingThreshold(userDto.getEnergyAlertingThreshold());

        return toDto(userRepository.save(foundUser));

    }

    public void deleteUser(Long id) {

        User foundUser = userRepository.findById(id)
                        .orElseThrow(() -> new UserNotFoundException("User not found!"));
        userRepository.delete(foundUser);
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

