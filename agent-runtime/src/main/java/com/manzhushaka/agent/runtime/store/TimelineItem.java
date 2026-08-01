package com.manzhushaka.agent.runtime.store;

import java.time.Instant;
import java.util.Map;

public record TimelineItem(
        long sequence,
        String kind,
        String role,
        String content,
        String eventType,
        String requestId,
        String agentCode,
        String actionCode,
        Instant createdAt,
        Map<String, Object> payload
) {
    public TimelineItem {
        payload = payload == null ? Map.of() : Map.copyOf(payload);
    }
}
