package com.cagritasoz.user_service.exception;

public class MissingIdentityClaimException extends RuntimeException {
    public MissingIdentityClaimException(String message) {
        super(message);
    }
}
