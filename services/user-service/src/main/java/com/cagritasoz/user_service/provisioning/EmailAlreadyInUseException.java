package com.cagritasoz.user_service.provisioning;

// A new registration's email address is held by an account that still exists in Keycloak, so the two systems disagree
// about whose address it is. Retrying does not help until an operator resolves it (409).
public class EmailAlreadyInUseException extends RuntimeException {

    public EmailAlreadyInUseException() {

        super("The email address is already in use by another account.");

    }
}
