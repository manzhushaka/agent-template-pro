package com.manzhushaka.agent.runtime.workflow;

/** Result of preparing a gated workflow write operation. */
public record WorkflowTaskPreparation(
        String taskId,
        int confirmationVersion,
        String confirmationSnapshotHash
) {
}
