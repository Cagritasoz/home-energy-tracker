package com.cagritasoz.user_service.provisioning;

public class MissingIdentityClaimException extends RuntimeException {
    public MissingIdentityClaimException(String message) {
        super(message);
    }
}
