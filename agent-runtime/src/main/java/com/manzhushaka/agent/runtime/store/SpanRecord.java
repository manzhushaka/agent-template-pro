package com.manzhushaka.agent.runtime.store;

import java.time.Instant;
import java.util.Map;

/**
 * A completed span. Content is intentionally absent: only references (ids, hashes, field
 * names, counts) and masked metadata are stored. Prompt and message text never appear here.
 */
public record SpanRecord(
        String id,
        String traceId,
        String parentSpanId,
        SpanType type,
        String name,
        SpanStatus status,
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
        Instant finishedAt,
        Instant createdAt
) {
    public SpanRecord {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
        resourceVersions = resourceVersions == null ? Map.of() : Map.copyOf(resourceVersions);
    }
}
