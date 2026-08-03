package com.manzhushaka.agent.runtime.store;

/** Stable span categories for observability. */
public enum SpanType {
    REQUEST,
    ROUTE,
    TASK,
    ACTION,
    TOOL,
    MODEL,
    RETRIEVAL,
    WORKFLOW,
    EVALUATION
}
