package com.cagritasoz.user_service.relay;

public record BatchResult(Outcome outcome, int published, boolean full, Throwable failure) {

    public enum Outcome {
        IDLE,
        PUBLISHED,
        FAILED,
        INTERRUPTED
    }

    public static BatchResult idle() {

        return new BatchResult(Outcome.IDLE, 0, false, null);

    }
}
