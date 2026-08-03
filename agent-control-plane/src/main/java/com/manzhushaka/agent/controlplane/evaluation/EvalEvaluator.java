package com.manzhushaka.agent.controlplane.evaluation;

import java.time.Instant;

public record EvalEvaluator(
        String id,
        String code,
        String displayName,
        String evaluatorType,
        String description,
        String status,
        String currentVersionId,
        String createdBy,
        Instant createdAt,
        Instant updatedAt
) {
}
