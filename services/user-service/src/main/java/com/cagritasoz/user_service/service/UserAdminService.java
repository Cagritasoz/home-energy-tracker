package com.cagritasoz.user_service.service;

import com.cagritasoz.user_service.dto.ParticipantResponse;
import com.cagritasoz.user_service.dto.UserAdminResponse;
import com.cagritasoz.user_service.entity.User;
import com.cagritasoz.user_service.entity.UserDeletionParticipant;
import com.cagritasoz.user_service.exception.UserNotFoundException;
import com.cagritasoz.user_service.repository.UserDeletionParticipantRepository;
import com.cagritasoz.user_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

// Operations an administrator performs on any account, as opposed to UserService, where a user
// works on their own. The ids here come from the URL, not from the JWT subject.
@Service
@RequiredArgsConstructor
public class UserAdminService {

    private final UserRepository userRepository;

    private final UserDeletionParticipantRepository participantRepository;

    @Transactional(readOnly = true)
    public UserAdminResponse getUser(UUID id) {

        User user = userRepository.findById(id)
                .orElseThrow(UserNotFoundException::new);

        List<ParticipantResponse> participants = participantRepository.findByIdUserIdOrderByIdParticipant(id).stream()
                .map(UserAdminService::toParticipantResponse)
                .toList();

        return toResponse(user, participants);
    }

    // The sort is fixed instead of coming from the request: a client-supplied sort property that
    // doesn't exist would end in a 500, and the id tiebreaker keeps page boundaries stable when
    // several accounts share a created_at.
    @Transactional(readOnly = true)
    public Page<UserAdminResponse> listUsers(int page, int size) {

        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))); // ORDER BY created_at DESC, id ASC

        Page<User> users = userRepository.findAll(pageRequest);

        // The participants of the whole page in one query, grouped by user; users without any get an empty list.
        Map<UUID, List<ParticipantResponse>> participantsByUser = participantRepository
                .findByIdUserIdIn(users.getContent().stream().map(User::getId).toList()).stream()
                .sorted(Comparator.comparing(p -> p.getId().participant()))
                .collect(Collectors.groupingBy(p -> p.getId().userId(),
                        Collectors.mapping(UserAdminService::toParticipantResponse, Collectors.toList())));

        return users.map(user -> toResponse(user, participantsByUser.getOrDefault(user.getId(), List.of())));
    }

    private static ParticipantResponse toParticipantResponse(UserDeletionParticipant participant) {
        return ParticipantResponse.builder()
                .participant(participant.getId().participant())
                .ackedAt(participant.getAckedAt())
                .waivedAt(participant.getWaivedAt())
                .build();
    }

    private UserAdminResponse toResponse(User user, List<ParticipantResponse> participants) {
        return UserAdminResponse.builder()
                .id(user.getId())
                .email(user.getEmail())
                .displayName(user.getDisplayName())
                .timezone(user.getTimezone())
                .status(user.getStatus())
                .version(user.getVersion())
                .deletionRequestedAt(user.getDeletionRequestedAt())
                .deletionScheduledFor(user.getDeletionScheduledFor())
                .deletionStartedAt(user.getDeletionStartedAt())
                .keycloakDeletedAt(user.getKeycloakDeletedAt())
                .deletedAt(user.getDeletedAt())
                .participants(participants)
                .createdAt(user.getCreatedAt())
                .updatedAt(user.getUpdatedAt())
                .build();
    }
}
