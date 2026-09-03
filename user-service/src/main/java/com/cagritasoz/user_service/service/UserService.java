package com.cagritasoz.user_service.service;

import com.cagritasoz.user_service.dto.UserDto;
import com.cagritasoz.user_service.entity.User;
import com.cagritasoz.user_service.exception.DuplicateEmailException;
import com.cagritasoz.user_service.exception.UserNotFoundException;
import com.cagritasoz.user_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Slf4j
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional
    public UserDto createUser(UserDto inputUser) {

        // Fast-path check: gives a clean, typed error in the common (non-racing) case
        // instead of letting a DB round-trip fail. NOT sufficient on its own — see
        // the DataIntegrityViolationException handler in GlobalExceptionHandler for why.
        if (userRepository.existsByEmail(inputUser.getEmail())) {
            throw new DuplicateEmailException("Email already in use: " + inputUser.getEmail());
        }

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

    @Transactional(readOnly = true)
    public UserDto getUserById(Long id) {

        User foundUser = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found!"));
        return toDto(foundUser);
    }

    @Transactional
    public UserDto updateUser(Long id, UserDto userDto) {

    User foundUser = userRepository.findById(id)
                .orElseThrow(() -> new UserNotFoundException("User not found!")); // Found user is managed

        // Only check when the email is actually changing - otherwise a user keeping
        // their own email would always "collide" with themselves since existsByEmail() would always return true.
        // A user changing their email could collide with existing emails, if email has changed pre-check is necessary.
        boolean emailChanged = !foundUser.getEmail().equals(userDto.getEmail());
        if (emailChanged && userRepository.existsByEmail(userDto.getEmail())) {
            throw new DuplicateEmailException("Email already in use: " + userDto.getEmail());
        }

        foundUser.setFirstName(userDto.getFirstName());
        foundUser.setLastName(userDto.getLastName());
        foundUser.setEmail(userDto.getEmail());
        foundUser.setAddress(userDto.getAddress());
        foundUser.setAlertsEnabled(userDto.isAlertsEnabled());
        foundUser.setEnergyAlertingThreshold(userDto.getEnergyAlertingThreshold());

        // No explicit save needed: foundUser is managed, so Hibernate's dirty
        // checking flushes these changes automatically at transaction commit.
        // UPDATE keyword is used not INSERT.
        return toDto(foundUser);
    }

    @Transactional
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

