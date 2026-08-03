package com.manzhushaka.agent.runtime.store;

import java.time.Instant;
import java.util.Map;

/** Append-only, redacted runtime audit fact. */
public record AuditRecord(
        String id,
        String visitorId,
        String taskId,
        String requestId,
        String eventType,
        String actorType,
        Map<String, Object> metadata,
        Instant createdAt
) {
    public AuditRecord {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
