package com.manzhushaka.agent.runtime.store;

import java.time.Instant;
import java.util.Map;

public record GraphCheckpointRecord(
        String graphThreadId,
        String checkpointId,
        Map<String, Object> state,
        String nodeId,
        String nextNodeId,
        Instant createdAt
) {
    public GraphCheckpointRecord {
        state = Map.copyOf(state);
    }
}
