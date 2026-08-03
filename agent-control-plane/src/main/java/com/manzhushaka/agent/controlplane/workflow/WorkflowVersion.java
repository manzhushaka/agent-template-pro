package com.manzhushaka.agent.controlplane.workflow;

import java.time.Instant;
import java.util.Map;

/** Immutable workflow version snapshot: DSL + resource bindings fixed at publish time. */
public record WorkflowVersion(
        String id,
        String workflowId,
        int versionNo,
        String status,
        String schemaVersion,
        String dslJson,
        Map<String, Object> resourceBindings,
        String description,
        String createdBy,
        Instant publishedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public WorkflowVersion {
        resourceBindings = Map.copyOf(resourceBindings == null ? Map.of() : resourceBindings);
    }
}
