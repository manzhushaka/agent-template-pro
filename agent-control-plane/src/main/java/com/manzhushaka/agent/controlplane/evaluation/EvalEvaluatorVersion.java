package com.manzhushaka.agent.controlplane.evaluation;

import java.time.Instant;
import java.util.Map;

public record EvalEvaluatorVersion(
        String id,
        String evaluatorId,
        int versionNo,
        String status,
        Map<String, Object> config,
        String createdBy,
        Instant createdAt
) {
    public EvalEvaluatorVersion {
        config = config == null ? Map.of() : Map.copyOf(config);
    }
}
