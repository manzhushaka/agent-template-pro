package com.manzhushaka.agent.runtime.workflow;

/** Supported workflow node kinds in DSL schema 1.0. */
public enum WorkflowNodeType {
    START,
    END,
    INPUT,
    VARIABLE_ASSIGN,
    LLM,
    CLASSIFIER,
    ACTION,
    MCP_TOOL,
    RETRIEVAL,
    PARALLEL
}
