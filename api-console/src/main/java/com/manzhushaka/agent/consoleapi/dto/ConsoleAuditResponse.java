package com.manzhushaka.agent.consoleapi.dto;

import java.time.Instant;
import java.util.Map;

public record ConsoleAuditResponse(
        String id,
        String requestId,
        String eventType,
        String actorType,
        Map<String, Object> metadata,
        Instant createdAt
) {
    public ConsoleAuditResponse {
        metadata = Map.copyOf(metadata);
    }
}
