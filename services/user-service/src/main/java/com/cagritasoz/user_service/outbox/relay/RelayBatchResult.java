package com.cagritasoz.user_service.outbox.relay;

public record RelayBatchResult(Outcome outcome, int published, boolean full, Throwable failure) {

    public enum Outcome {
        IDLE,
        PUBLISHED,
        FAILED,
        INTERRUPTED
    }

    public static RelayBatchResult idle() {

        return new RelayBatchResult(Outcome.IDLE, 0, false, null);

    }
}
