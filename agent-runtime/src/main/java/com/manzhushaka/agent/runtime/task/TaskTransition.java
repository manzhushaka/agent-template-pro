package com.manzhushaka.agent.runtime.task;

import java.time.Instant;

public record TaskTransition(
        String externalRef,
        String resultSummary,
        String errorCode,
        Instant executionLeaseUntil,
        Instant nextRecoveryAt,
        int recoveryAttemptDelta
) {
    public TaskTransition {
        if (recoveryAttemptDelta < 0) {
            throw new IllegalArgumentException("recoveryAttemptDelta cannot be negative");
        }
    }

    public static TaskTransition none() {
        return new TaskTransition(null, null, null, null, null, 0);
    }

    public static TaskTransition completed(String externalRef, String resultSummary) {
        return new TaskTransition(externalRef, resultSummary, null, null, null, 0);
    }

    public static TaskTransition failed(String errorCode) {
        return new TaskTransition(null, null, errorCode, null, null, 0);
    }

    public static TaskTransition recovery(String errorCode, Instant nextRecoveryAt) {
        return new TaskTransition(null, null, errorCode, null, nextRecoveryAt, 1);
    }
}
