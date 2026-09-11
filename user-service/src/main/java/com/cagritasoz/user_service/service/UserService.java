package com.cagritasoz.user_service.service;

import com.cagritasoz.user_service.dto.UserRequest;
import com.cagritasoz.user_service.dto.UserResponse;
import com.cagritasoz.user_service.entity.User;
import com.cagritasoz.user_service.exception.DuplicateEmailException;
import com.cagritasoz.user_service.exception.UserNotFoundException;
import com.cagritasoz.user_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

@Service
@RequiredArgsConstructor
public class UserService {

    private final UserRepository userRepository;

    @Transactional
    public UserResponse createUser(UserRequest request) {

        // Fast-path check: gives a clean, typed error in the common (non-racing) case instead of
        // letting a DB round-trip fail. NOT sufficient on its own - see the
        // DataIntegrityViolationException handler in GlobalExceptionHandler for why.
        if (userRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException();
        }

        final User user = User.builder()
                .firstName(request.firstName())
                .lastName(request.lastName())
                .email(request.email())
                .address(request.address())
                .build();

        // id/createdAt/updatedAt are populated by the DB/Hibernate on save, not before.
        return toResponse(userRepository.save(user));
    }

    // Unlike getAlertRules, there's no parent to scope by and no existsById check needed -
    // users is the top-level resource. findAll() comes free from JpaRepository, no custom
    // repository method required.
    @Transactional(readOnly = true)
    public List<UserResponse> getUsers() {
        return userRepository.findAll()
                .stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional(readOnly = true)
    public UserResponse getUserById(Long id) {

        User user = userRepository.findById(id)
                .orElseThrow(UserNotFoundException::new);

        return toResponse(user);
    }

    @Transactional
    public UserResponse updateUser(Long id, UserRequest request) {

        User user = userRepository.findById(id)
                .orElseThrow(UserNotFoundException::new); // managed entity

        // Only check when the email is actually changing - otherwise a user keeping their own
        // email would always "collide" with themselves since existsByEmail() would always
        // return true for it.
        boolean emailChanged = !user.getEmail().equals(request.email());
        if (emailChanged && userRepository.existsByEmail(request.email())) {
            throw new DuplicateEmailException();
        }

        user.setFirstName(request.firstName());
        user.setLastName(request.lastName());
        user.setEmail(request.email());
        user.setAddress(request.address());

        // Managed entity - dirty checking flushes these changes at commit (UPDATE, not INSERT),
        // and @UpdateTimestamp bumps updated_at along with it. No save() call needed.
        return toResponse(user);
    }

    @Transactional
    public void deleteUser(Long id) {

        User user = userRepository.findById(id)
                .orElseThrow(UserNotFoundException::new);

        userRepository.delete(user);
    }

    private UserResponse toResponse(User user) {
        return UserResponse.builder()
                .id(user.getId())
                .firstName(user.getFirstName())
                .lastName(user.getLastName())
                .email(user.getEmail())
                .address(user.getAddress())
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
