package com.manzhushaka.agent.runtime.store;

import java.time.Instant;

/** Bounded trace query with server-side paging. */
public record SpanQuery(
        int page,
        int size,
        SpanType type,
        SpanStatus status,
        String conversationId,
        String taskId,
        String requestId,
        String agentCode,
        String keyword,
        Instant from,
        Instant to
) {
    public SpanQuery {
        page = Math.max(1, page);
        size = Math.max(1, Math.min(size, 100));
    }
}
