package com.manzhushaka.agent.runtime.recovery;

import java.time.Instant;

public record TaskRecoveryResult(
        TaskRecoveryOutcome outcome,
        String externalRef,
        String resultSummary,
        String errorCode,
        Instant retryAt
) {
    public static TaskRecoveryResult succeeded(String externalRef, String summary) {
        return new TaskRecoveryResult(TaskRecoveryOutcome.SUCCEEDED, externalRef, summary, null, null);
    }

    public static TaskRecoveryResult failed(String externalRef, String errorCode) {
        return new TaskRecoveryResult(TaskRecoveryOutcome.FAILED, externalRef, null, errorCode, null);
    }

    public static TaskRecoveryResult pending(String externalRef, Instant retryAt) {
        return new TaskRecoveryResult(TaskRecoveryOutcome.STILL_PENDING, externalRef, null, null, retryAt);
    }

    public static TaskRecoveryResult unknown(Instant retryAt) {
        return new TaskRecoveryResult(TaskRecoveryOutcome.UNKNOWN, null, null, "RESULT_UNKNOWN", retryAt);
    }
}
