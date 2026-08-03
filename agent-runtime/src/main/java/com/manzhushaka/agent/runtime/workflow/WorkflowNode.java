package com.manzhushaka.agent.runtime.workflow;

import java.util.Map;

/** A node in a workflow DSL. Config is validated per node type by {@link WorkflowDslValidator}. */
public record WorkflowNode(String id, WorkflowNodeType type, String name, Map<String, Object> config) {
    public WorkflowNode {
        config = Map.copyOf(config == null ? Map.of() : config);
    }
}
