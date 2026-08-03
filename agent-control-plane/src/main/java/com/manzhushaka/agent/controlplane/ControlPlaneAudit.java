package com.manzhushaka.agent.controlplane;

import java.time.Instant;
import java.util.Map;

public record ControlPlaneAudit(
        String id,
        String actor,
        String action,
        String resourceType,
        String resourceId,
        Map<String, Object> metadata,
        Instant createdAt
) {
    public ControlPlaneAudit {
        metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
}
