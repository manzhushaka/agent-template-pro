package com.manzhushaka.agent.controlplane.evaluation;

import java.time.Instant;

public record EvalDataset(
        String id,
        String code,
        String displayName,
        String description,
        String currentVersionId,
        String status,
        String createdBy,
        Instant createdAt,
        Instant updatedAt
) {
}
