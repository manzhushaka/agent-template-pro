package com.manzhushaka.agent.runtime.workflow;

/** Per-node execution lifecycle. WAITING_* nodes park the run until input or confirmation arrives. */
public enum WorkflowNodeRunStatus {
    PENDING,
    RUNNING,
    SUCCEEDED,
    FAILED,
    SKIPPED,
    WAITING_INPUT,
    WAITING_CONFIRMATION
}
