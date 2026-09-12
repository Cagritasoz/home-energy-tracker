package com.cagritasoz.user_service.service;

import com.cagritasoz.user_service.dto.AlertRuleRequest;
import com.cagritasoz.user_service.dto.AlertRuleResponse;
import com.cagritasoz.user_service.entity.AlertRule;
import com.cagritasoz.user_service.event.AlertRuleChangedPayload;
import com.cagritasoz.user_service.event.AlertRuleDeletedPayload;
import com.cagritasoz.user_service.exception.AlertRuleNotFoundException;
import com.cagritasoz.user_service.exception.DuplicateAlertRuleException;
import com.cagritasoz.user_service.exception.UserNotFoundException;
import com.cagritasoz.user_service.model.AlertScope;
import com.cagritasoz.user_service.model.OutboxAggregateType;
import com.cagritasoz.user_service.model.OutboxEventType;
import com.cagritasoz.user_service.repository.AlertRuleRepository;
import com.cagritasoz.user_service.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class AlertRuleService {

    private final AlertRuleRepository alertRuleRepository;
    private final UserRepository userRepository;
    private final OutboxService outboxService;

    @Transactional
    public AlertRuleResponse createAlertRule(Long userId, AlertRuleRequest request) {

        // Only createAlertRule needs this: the rule doesn't exist yet, so no lookup for alert_rules table can prove the
        // user does. The read/update/delete paths get it for free - findOwned returning a row
        // already implies a valid user (the FK guarantees it) because this check enforces user to exist before inserting a rule.
        if(!userRepository.existsById(userId)) {
            throw new UserNotFoundException();
        }

        // TODO: Refactor once AlertScope enum grows.
        AlertScope scope = AlertScope.ALL_DEVICES;
        if(alertRuleRepository.existsByUserIdAndEvaluationWindowAndScope(userId, request.evaluationWindow(), scope)) {
            throw new DuplicateAlertRuleException();
        }

        final AlertRule alertRule = AlertRule.builder()
                .user(userRepository.getReferenceById(userId))
                .name(resolveName(request))
                .evaluationWindow(request.evaluationWindow())
                .thresholdKwh(request.thresholdKwh())
                .scope(scope)
                .enabled(Objects.requireNonNullElse(request.enabled(), Boolean.TRUE)) // If enabled is omitted (null), fall back to the default matching the database.
                .build();

        AlertRule saved = alertRuleRepository.save(alertRule); // Id populated after save method.

        // aggregateId is the rule's own id (this row's identity); partitionKey is the OWNING
        // user's id, not the rule's - deliberately different values. That's what keeps this
        // event on the same Kafka partition as every other event for this user (including their
        // eventual USER_DELETED), regardless of partition count.
        outboxService.recordEvent(OutboxAggregateType.ALERT_RULE, saved.getId(), userId, OutboxEventType.ALERT_RULE_CREATED,
                AlertRuleChangedPayload.builder()
                        .ruleId(saved.getId())
                        .userId(userId)
                        .name(saved.getName())
                        .evaluationWindow(saved.getEvaluationWindow())
                        .thresholdKwh(saved.getThresholdKwh())
                        .scope(saved.getScope())
                        .enabled(saved.getEnabled())
                        .occurredAt(Instant.now())
                        .build());

        return toResponse(saved);

    }

    @Transactional(readOnly = true)
    public List<AlertRuleResponse> getAlertRules(Long userId) {

        // 404 if parent does not exist.
        if(!userRepository.existsById(userId)) {
            throw new UserNotFoundException();
        }

        // Returning an empty list if a user does not have any rules is totally fine.
        return alertRuleRepository.findByUserId(userId)
                .stream()
                .map(this::toResponse)
                .toList();

    }

    @Transactional(readOnly = true)
    public AlertRuleResponse getAlertRuleById(Long userId, Long ruleId) {

        return toResponse(findOwned(userId, ruleId));

    }

    @Transactional
    public AlertRuleResponse updateAlertRule(Long userId, Long ruleId, AlertRuleRequest request) {

        AlertRule alertRule = findOwned(userId, ruleId);

        // Only re-check uniqueness when the window is actually changing. Skipping the query when
        // it isn't is a real optimization, not just style - it's also what makes the plain
        // existsBy (no self-exclusion needed with NotId) correct: this check only runs while
        // alertRule.getEvaluationWindow() (the row's current, not-yet-overwritten value) still
        // differs from request.evaluationWindow() (what we're searching for), so the target row
        // can never be the one the query finds - it structurally can't match its own old value
        // against the new one it's being compared to because we make sure it changes first.
        boolean windowChanged = alertRule.getEvaluationWindow() != request.evaluationWindow();
        if (windowChanged && alertRuleRepository.existsByUserIdAndEvaluationWindowAndScope(
                userId, request.evaluationWindow(), alertRule.getScope())) {
            throw new DuplicateAlertRuleException();
        }

        alertRule.setName(resolveName(request));
        alertRule.setEvaluationWindow(request.evaluationWindow());
        alertRule.setThresholdKwh(request.thresholdKwh());
        alertRule.setEnabled(Objects.requireNonNullElse(request.enabled(), Boolean.TRUE));

        // Instant.now() for occurredAt field, not alertRule.getUpdatedAt() - same reasoning as UserService.updateUser:
        // @UpdateTimestamp only stamps the new value at flush (commit), which hasn't happened yet
        // at this point in the method and would return stale data, would not correctly reflect last updated at time.
        // Using Instant.now() across create, update, and delete ensures
        // a perfectly uniform timeline generated by the application clock. If down-the-line
        // business logic ever demands that the event payload's timestamp exactly matches the
        // database-generated 'updated_at' column (to prevent minor millisecond clock drift
        // between the app server and DB server), calling '.flush()'
        // right before this block to force Hibernate to populate 'updatedAt field'
        // and map that value instead. For sequential cache needs, uniform application
        // timestamps work at the moment.
        outboxService.recordEvent(OutboxAggregateType.ALERT_RULE, alertRule.getId(), userId, OutboxEventType.ALERT_RULE_UPDATED,
                AlertRuleChangedPayload.builder()
                        .ruleId(alertRule.getId())
                        .userId(userId)
                        .name(alertRule.getName())
                        .evaluationWindow(alertRule.getEvaluationWindow())
                        .thresholdKwh(alertRule.getThresholdKwh())
                        .scope(alertRule.getScope())
                        .enabled(alertRule.getEnabled())
                        .occurredAt(Instant.now())
                        .build());

        // Managed entity, in an active transaction (the @Transactional above matters here, not
        // just as decoration) - dirty checking flushes these changes at commit. No save() call.
        return toResponse(alertRule);
    }

    @Transactional
    public void deleteAlertRule(Long userId, Long ruleId) {

        AlertRule alertRule = findOwned(userId, ruleId);

        outboxService.recordEvent(OutboxAggregateType.ALERT_RULE, alertRule.getId(), userId, OutboxEventType.ALERT_RULE_DELETED,
                AlertRuleDeletedPayload.builder()
                        .ruleId(alertRule.getId())
                        .userId(userId)
                        .occurredAt(Instant.now())
                        .build());

        alertRuleRepository.delete(alertRule);
    }

    // One query settles existence and ownership together: a row comes back only if the rule
    // exists AND its user_id matches. A rule owned by someone else looks identical to a missing
    // one - same 404, so ownership is never leaked. Parameter order (userId, ruleId) matches
    // every other method in this class, unlike the repository method it calls underneath -
    // findByIdAndUserId's (id, userId) order is fixed by Spring Data's name-derived binding.
    private AlertRule findOwned(Long userId, Long ruleId) {
        return alertRuleRepository.findByIdAndUserId(ruleId, userId)
                .orElseThrow(AlertRuleNotFoundException::new);
    }

    private AlertRuleResponse toResponse(AlertRule alertRule) {
        return AlertRuleResponse.builder()
                .id(alertRule.getId())
                // user is a LAZY @ManyToOne, so getUser() returns an uninitialized proxy. Reading
                // its id is free - it's the FK value already loaded on the row, no SELECT. Works
                // the same whether the rule came from save() or from findOwned(). Any other getter
                // like getEmail would force the proxy to initialize and issue a query.
                .userId(alertRule.getUser().getId())
                .name(alertRule.getName())
                .evaluationWindow(alertRule.getEvaluationWindow())
                .thresholdKwh(alertRule.getThresholdKwh())
                .scope(alertRule.getScope())
                .enabled(alertRule.getEnabled())
                .createdAt(alertRule.getCreatedAt())
                .updatedAt(alertRule.getUpdatedAt())
                .build();
    }
    // Falls back to a "<window label> / <threshold> kWh" name when the client omits one -
    // EvaluationWindow.getLabel() ("1 hour"), not name()/toString() ("ONE_HOUR").
    private String resolveName(AlertRuleRequest request) {
        if(request.name() != null && !request.name().isBlank()) {
            return request.name();
        }
        return request.evaluationWindow().getLabel() + " / " + request.thresholdKwh() + " kWh";
    }
}
