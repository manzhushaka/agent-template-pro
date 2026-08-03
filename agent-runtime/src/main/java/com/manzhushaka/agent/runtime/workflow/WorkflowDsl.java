package com.manzhushaka.agent.runtime.workflow;

import java.util.List;
import java.util.Map;

/**
 * Versioned workflow definition. Schema 1.0 is a DAG: edges are unique by (from,to),
 * cycles are rejected at publish time, and bounded iteration is a later extension.
 */
public record WorkflowDsl(
        String schemaVersion,
        String code,
        String displayName,
        List<WorkflowNode> nodes,
        List<WorkflowEdge> edges
) {
    public static final String SUPPORTED_SCHEMA_VERSION = "1.0";

    public WorkflowDsl {
        nodes = List.copyOf(nodes);
        edges = List.copyOf(edges);
    }
}
