package com.cagritasoz.user_service.aspect;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

// Marks a method as exempt from LoggingAspect's generic before/after-returning/after-throwing
// logging. For methods invoked on a fixed timer regardless of whether there's anything to do
// (OutboxRelay.relay() and the OutboxService methods it calls every tick) - each invocation isn't
// a meaningful event the way a request-driven method's call is, so logging every one is noise,
// not signal. RUNTIME retention is required for LoggingAspect's @annotation() pointcut to see it
// via reflection.
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
public @interface SkipLogging {
}
