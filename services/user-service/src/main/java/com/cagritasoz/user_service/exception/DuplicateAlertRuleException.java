package com.cagritasoz.user_service.exception;

public class DuplicateAlertRuleException extends RuntimeException {
    public DuplicateAlertRuleException() {
        super("An alert rule for this window already exists.");
    }
}
