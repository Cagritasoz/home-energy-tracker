package com.cagritasoz.user_service.exception;

public class AlertRuleNotFoundException extends RuntimeException {
    public AlertRuleNotFoundException() {
        super("Alert rule not found!");
    }
}
