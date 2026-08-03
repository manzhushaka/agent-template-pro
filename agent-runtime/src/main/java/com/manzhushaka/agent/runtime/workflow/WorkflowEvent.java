package com.manzhushaka.agent.runtime.workflow;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Durable workflow progress event consumed by the Console SSE stream. */
public record WorkflowEvent(
        String id,
        String runId,
        long sequence,
        String type,
        String nodeId,
        Map<String, Object> payload,
        Instant createdAt
) {
    public WorkflowEvent {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (payload != null) {
            copy.putAll(payload);
        }
        payload = Collections.unmodifiableMap(copy);
    }
}
