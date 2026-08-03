package com.manzhushaka.agent.controlplane.workflow;

import java.time.Instant;

/** Workflow container metadata; DSL snapshots live in versions. */
public record WorkflowDefinition(
        String id,
        String code,
        String displayName,
        String description,
        String status,
        String currentVersionId,
        String createdBy,
        Instant createdAt,
        Instant updatedAt
) {
}
