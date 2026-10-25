package com.cagritasoz.user_service.deletion;

// A write was refused because the account is DELETION_SCHEDULED: during the grace period the account is
// readable (and cancellable) but read-only. 409 rather than 403: the caller is allowed to write in general, the
// account's current state is what blocks it, and cancelling the deletion makes the same request succeed.
public class DeletionScheduledException extends RuntimeException {

    public DeletionScheduledException() {

        super("The account is scheduled for deletion and cannot be changed. Cancel the deletion first.");

    }
}
