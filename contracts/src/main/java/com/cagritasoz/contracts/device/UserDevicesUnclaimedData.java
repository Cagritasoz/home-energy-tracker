package com.cagritasoz.contracts.device;

import lombok.Builder;

// "data" of a UserDevicesUnclaimed event: device-service's confirmation, in the account-deletion saga, that it no
// longer holds anything personal for the user. It answers UserDeletionStarted (the envelope's causationId is that
// event's id).
//
// What the confirmation promises: every device the user had claimed has been released (the device record itself
// stays, unowned, so it can be claimed again), the user's personal data about those devices is gone, the devices'
// credentials are revoked, and new claims for this user are refused. A service sends it even when it found
// nothing to release: the saga waits for the confirmation, not for the work.
//
// The user is the envelope's aggregateId; aggregateVersion is null (device-service does not own the user).
//
// devicesUnclaimed is informational (how many devices were released, 0 is normal): no consumer may depend on it.
@Builder
public record UserDevicesUnclaimedData(
        int devicesUnclaimed
) {

    // Shape version of this payload; goes into the envelope's schemaVersion. Bumped only for a breaking change.
    public static final int SCHEMA_VERSION = 1;
}
