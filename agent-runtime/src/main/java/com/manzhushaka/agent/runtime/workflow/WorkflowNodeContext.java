package com.manzhushaka.agent.runtime.workflow;

import java.util.Map;

/** Immutable context handed to a node handler for one execution attempt. */
public record WorkflowNodeContext(
        WorkflowRun run,
        WorkflowNode node,
        Map<String, Object> variables,
        String visitorRef,
        String requestId,
        WorkflowConfirmationGate confirmationGate,
        WorkflowRunStore runStore
) {
    public WorkflowNodeContext {
        variables = Map.copyOf(variables);
    }
}
