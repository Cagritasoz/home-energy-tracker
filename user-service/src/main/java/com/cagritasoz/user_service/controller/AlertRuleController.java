package com.cagritasoz.user_service.controller;

import com.cagritasoz.user_service.dto.AlertRuleRequest;
import com.cagritasoz.user_service.dto.AlertRuleResponse;
import com.cagritasoz.user_service.service.AlertRuleService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("api/v1/users/{userId}/alert-rules")
@RequiredArgsConstructor
public class AlertRuleController {

    private final AlertRuleService alertRuleService;

    @PostMapping
    public ResponseEntity<AlertRuleResponse> createAlertRule(@PathVariable Long userId,
                                                             @Valid @RequestBody AlertRuleRequest ruleRequest) {

        AlertRuleResponse ruleResponse = alertRuleService.createAlertRule(userId, ruleRequest);

        return ResponseEntity.status(HttpStatus.CREATED).body(ruleResponse);

    }
}
