package com.manzhushaka.agent.runtime.workflow;

/** Raised when a workflow DSL fails static validation; maps to a 400 on Console APIs. */
public class WorkflowValidationException extends RuntimeException {
    public WorkflowValidationException(String message) {
        super(message);
    }
}
