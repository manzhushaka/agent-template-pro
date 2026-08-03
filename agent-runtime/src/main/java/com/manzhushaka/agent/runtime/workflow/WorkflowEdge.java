package com.manzhushaka.agent.runtime.workflow;

/**
 * Directed edge. {@code condition} is optional and only allowed when the source node has
 * multiple outgoing edges; "default" matches when no earlier condition matched.
 */
public record WorkflowEdge(String from, String to, String condition) {
    public WorkflowEdge {
        condition = condition == null || condition.isBlank() ? null : condition.trim();
    }

    public String key() {
        return from + "->" + to;
    }
}
