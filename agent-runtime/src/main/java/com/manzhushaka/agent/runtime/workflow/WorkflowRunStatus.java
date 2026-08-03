package com.manzhushaka.agent.runtime.workflow;

/** Durable workflow run lifecycle. */
public enum WorkflowRunStatus {
    PENDING,
    RUNNING,
    PAUSED,
    STOPPED,
    SUCCEEDED,
    FAILED
}
