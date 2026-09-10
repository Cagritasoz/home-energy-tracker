package com.cagritasoz.user_service.model;

// What an alert rule's threshold is measured against. Only ALL_DEVICES at the moment (sum of every
// device the user owns); PER_DEVICE / PER_DEVICE_TYPE / PER_LOCATION are the planned additions,
// paired with the deferred alert_rules.scope_ref column. Named AlertScope, not Scope, so it
// doesn't collide with Spring's own scope vocabulary - parallels device-service's DeviceType in terms of package structure.
public enum AlertScope {
    ALL_DEVICES
}
