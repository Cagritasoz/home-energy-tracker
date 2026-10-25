package com.cagritasoz.user_service.provisioning;

public class EmailNotVerifiedException extends RuntimeException {
    public EmailNotVerifiedException() {
        super("Email is not verified.");
    }
}
