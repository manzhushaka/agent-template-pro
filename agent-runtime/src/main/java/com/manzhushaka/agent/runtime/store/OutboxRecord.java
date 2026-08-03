package com.manzhushaka.agent.runtime.store;

import java.time.Instant;
import java.util.Map;

public record OutboxRecord(
        String id,
        String aggregateType,
        String aggregateId,
        String eventType,
        Map<String, Object> payload,
        OutboxStatus status,
        int attemptCount,
        Instant availableAt,
        String leaseOwner,
        Instant leaseUntil,
        String lastError,
        Instant createdAt
) {
    public OutboxRecord {
        payload = Map.copyOf(payload);
    }
}
