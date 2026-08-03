package com.manzhushaka.agent.runtime.workflow;

/**
 * SPI for one node kind. Handlers are implemented where their dependencies live (model adapters,
 * domain actions, MCP transport, knowledge retrieval) and must never bypass the confirmation gate
 * for write operations.
 */
public interface WorkflowNodeHandler {
    boolean supports(WorkflowNodeType type);

    WorkflowNodeResult execute(WorkflowNodeContext context);
}
