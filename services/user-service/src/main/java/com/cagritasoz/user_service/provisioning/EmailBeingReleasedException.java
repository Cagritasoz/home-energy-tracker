package com.cagritasoz.user_service.provisioning;

// A new registration's email address is held by an account that no longer exists in Keycloak and is being removed.
// The address will be free shortly; the client should try again (503 with Retry-After).
public class EmailBeingReleasedException extends RuntimeException {

    public EmailBeingReleasedException() {

        super("The email address is held by an account that is being removed. Please try again shortly.");

    }
}
