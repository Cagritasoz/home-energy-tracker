package com.cagritasoz.user_service.exception;

// A cancel arrived after deletion_scheduled_for but before the promoter moved the row to DELETING. The deadline is
// the user's last moment to cancel, so the request is refused even though nothing has been deleted yet.
public class DeletionDeadlinePassedException extends RuntimeException {

    public DeletionDeadlinePassedException() {

        super("The grace period has ended, so the deletion can no longer be cancelled.");

    }
}
