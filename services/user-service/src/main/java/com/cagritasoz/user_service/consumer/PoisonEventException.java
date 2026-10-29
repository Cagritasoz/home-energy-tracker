package com.cagritasoz.user_service.consumer;

// A record that can never be applied, however often it is retried: it is not JSON, it lacks a field every event has,
// or it is a version of an event this service does not understand. The error handler does not retry it: it goes to
// the topic's dead-letter topic at once, and the consumer moves on to the next record.
//
// The message names what is wrong with the record and never quotes any of it: a dead-letter record carries the
// exception message in a header, and an event's payload can hold an email address.
public class PoisonEventException extends RuntimeException {

    public PoisonEventException(String message) {
        super(message);
    }

    public PoisonEventException(String message, Throwable cause) {
        super(message, cause);
    }
}
