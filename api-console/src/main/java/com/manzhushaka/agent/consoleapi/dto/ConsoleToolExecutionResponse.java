package com.manzhushaka.agent.consoleapi.dto;

import java.time.Instant;
import java.util.Map;

public record ConsoleToolExecutionResponse(
        String id,
        String toolCode,
        String toolVersionId,
        String status,
        Map<String, Object> inputSummary,
        Map<String, Object> outputSummary,
        String externalRef,
        String traceId,
        Instant startedAt,
        Instant finishedAt
) {
    public ConsoleToolExecutionResponse {
        inputSummary = Map.copyOf(inputSummary);
        outputSummary = Map.copyOf(outputSummary);
    }
}
