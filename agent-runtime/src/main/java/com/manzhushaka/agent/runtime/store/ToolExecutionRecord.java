package com.manzhushaka.agent.runtime.store;

import java.time.Instant;
import java.util.Map;

/** Redacted execution fact; input and output fields must contain summaries only. */
public record ToolExecutionRecord(
        String id,
        String taskId,
        String conversationId,
        String toolCode,
        String toolVersionId,
        ToolExecutionStatus status,
        Map<String, Object> inputSummary,
        Map<String, Object> outputSummary,
        String externalRef,
        String traceId,
        Instant startedAt,
        Instant finishedAt
) {
    public ToolExecutionRecord {
        inputSummary = inputSummary == null ? Map.of() : Map.copyOf(inputSummary);
        outputSummary = outputSummary == null ? Map.of() : Map.copyOf(outputSummary);
    }

    public ToolExecutionRecord complete(
            ToolExecutionStatus completedStatus,
            Map<String, Object> completedOutput,
            String completedExternalRef,
            Instant completedAt
    ) {
        if (completedStatus == ToolExecutionStatus.STARTED) {
            throw new IllegalArgumentException("completed status cannot be STARTED");
        }
        return new ToolExecutionRecord(
                id, taskId, conversationId, toolCode, toolVersionId, completedStatus,
                inputSummary, completedOutput, completedExternalRef, traceId, startedAt, completedAt
        );
    }
}
