package com.cagritasoz.user_service.controller;

import com.cagritasoz.user_service.dto.AlertRuleRequest;
import com.cagritasoz.user_service.dto.AlertRuleResponse;
import com.cagritasoz.user_service.service.AlertRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("api/v1/users/{userId}/alert-rules")
@RequiredArgsConstructor
public class AlertRuleController {

    private final AlertRuleService alertRuleService;

    @PostMapping
    public ResponseEntity<AlertRuleResponse> createAlertRule(@PathVariable Long userId,
                                                             @Valid @RequestBody AlertRuleRequest alertRuleRequest) {

        AlertRuleResponse createdAlertRule = alertRuleService.createAlertRule(userId, alertRuleRequest);

        return ResponseEntity.status(HttpStatus.CREATED).body(createdAlertRule);

    }

    @GetMapping
    public ResponseEntity<List<AlertRuleResponse>> getAlertRules(@PathVariable Long userId) {

        List<AlertRuleResponse> foundAlertRules = alertRuleService.getAlertRules(userId);

        return ResponseEntity.ok(foundAlertRules);

    }

    @GetMapping("/{ruleId}")
    public ResponseEntity<AlertRuleResponse> getAlertRuleById(@PathVariable Long userId,
                                                              @PathVariable Long ruleId) {

        AlertRuleResponse foundAlertRule = alertRuleService.getAlertRuleById(userId, ruleId);

        return ResponseEntity.ok(foundAlertRule);

    }

    @PutMapping("/{ruleId}")
    public ResponseEntity<AlertRuleResponse> updateAlertRule(@PathVariable Long userId,
                                                             @PathVariable Long ruleId,
                                                             @Valid @RequestBody AlertRuleRequest alertRuleRequest) {

        AlertRuleResponse updatedAlertRule = alertRuleService.updateAlertRule(userId, ruleId, alertRuleRequest);

        return ResponseEntity.ok(updatedAlertRule);

    }

    @DeleteMapping("/{ruleId}")
    public ResponseEntity<Void> deleteAlertRule(@PathVariable Long userId,
                                                @PathVariable Long ruleId) {

        alertRuleService.deleteAlertRule(userId, ruleId);

        return ResponseEntity.noContent().build();

    }
}
