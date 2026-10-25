package com.cagritasoz.user_service.admin;

import com.cagritasoz.user_service.deletion.UserDeletionParticipant;
import com.cagritasoz.user_service.deletion.UserDeletionParticipantRepository;
import com.cagritasoz.user_service.user.User;
import com.cagritasoz.user_service.user.UserNotFoundException;
import com.cagritasoz.user_service.user.UserRepository;
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

// Operations an administrator performs on any account, as opposed to UserProfileService, where a user
// works on their own. The ids here come from the URL, not from the JWT subject.
@Service
@RequiredArgsConstructor
public class AdminUserService {

    private final UserRepository userRepository;

    private final UserDeletionParticipantRepository participantRepository;

    @Transactional(readOnly = true)
    public AdminUserResponse getUser(UUID id) {

        User user = userRepository.findById(id)
                .orElseThrow(UserNotFoundException::new);

        List<DeletionParticipantResponse> participants = participantRepository.findByIdUserIdOrderByIdParticipant(id).stream()
                .map(AdminUserService::toParticipantResponse)
                .toList();

        return toResponse(user, participants);
    }

    // The sort is fixed instead of coming from the request: a client-supplied sort property that
    // doesn't exist would end in a 500, and the id tiebreaker keeps page boundaries stable when
    // several accounts share a created_at.
    @Transactional(readOnly = true)
    public Page<AdminUserResponse> listUsers(int page, int size) {

        PageRequest pageRequest = PageRequest.of(page, size,
                Sort.by(Sort.Direction.DESC, "createdAt").and(Sort.by("id"))); // ORDER BY created_at DESC, id ASC

        Page<User> users = userRepository.findAll(pageRequest);

        // The participants of the whole page in one query, grouped by user; users without any get an empty list.
        Map<UUID, List<DeletionParticipantResponse>> participantsByUser = participantRepository
                .findByIdUserIdIn(users.getContent().stream().map(User::getId).toList()).stream()
                .sorted(Comparator.comparing(p -> p.getId().participant()))
                .collect(Collectors.groupingBy(p -> p.getId().userId(),
                        Collectors.mapping(AdminUserService::toParticipantResponse, Collectors.toList())));

        return users.map(user -> toResponse(user, participantsByUser.getOrDefault(user.getId(), List.of())));
    }

    private static DeletionParticipantResponse toParticipantResponse(UserDeletionParticipant participant) {
        return DeletionParticipantResponse.builder()
                .participant(participant.getId().participant())
                .ackedAt(participant.getAckedAt())
                .waivedAt(participant.getWaivedAt())
                .build();
    }

    private AdminUserResponse toResponse(User user, List<DeletionParticipantResponse> participants) {
        return AdminUserResponse.builder()
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
