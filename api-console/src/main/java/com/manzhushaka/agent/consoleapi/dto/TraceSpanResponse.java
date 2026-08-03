package com.manzhushaka.agent.consoleapi.dto;

import java.time.Instant;
import java.util.Map;

public record TraceSpanResponse(
        String id,
        String traceId,
        String spanType,
        String name,
        String status,
        String visitorRef,
        String conversationId,
        String taskId,
        String requestId,
        String agentCode,
        String actionCode,
        String toolCode,
        String modelProvider,
        String modelName,
        int inputTokens,
        int outputTokens,
        int totalTokens,
        long durationMs,
        String errorCode,
        Map<String, Object> resourceVersions,
        Map<String, Object> metadata,
        Instant startedAt,
        Instant finishedAt
) {
}
