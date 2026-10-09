package com.cagritasoz.user_service.exception;

// Thrown for any account that is not ACTIVE, i.e. DELETING (the deletion was requested and is in progress) or
// DELETED (the saga finished). The token itself can still be perfectly valid - access tokens outlive the
// account for a few minutes - but the account behind it is gone, so every endpoint answers 410 Gone.
// One exception for both states on purpose: to the client they mean the same thing.
public class AccountDeletedException extends RuntimeException {

    public AccountDeletedException() {

        super("Account has been deleted or is being deleted.");

    }
}
