package com.manzhushaka.agent.controlplane.evaluation;

import java.time.Instant;

public record EvalDatasetVersion(
        String id,
        String datasetId,
        int versionNo,
        String status,
        String description,
        String createdBy,
        Instant createdAt,
        Instant updatedAt
) {
}
