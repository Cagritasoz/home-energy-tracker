package com.cagritasoz.user_service.exception;

public class DuplicateEmailException extends RuntimeException {
    public DuplicateEmailException() {
        super("Email already in use!");
    }
}
