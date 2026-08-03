package com.manzhushaka.agent.runtime.workflow;

import java.time.Instant;
import java.util.Map;

/** Durable per-node fact for a workflow run; the write gate links to an AgentTask when confirmed. */
public record WorkflowNodeRun(
        String id,
        String runId,
        String nodeId,
        WorkflowNodeType nodeType,
        WorkflowNodeRunStatus status,
        Map<String, Object> input,
        Map<String, Object> output,
        String confirmationTaskId,
        int confirmationVersion,
        String confirmationSnapshotHash,
        int retryCount,
        String errorCode,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public WorkflowNodeRun {
        input = Map.copyOf(input == null ? Map.of() : input);
        output = Map.copyOf(output == null ? Map.of() : output);
    }

    public WorkflowNodeRun withStatus(WorkflowNodeRunStatus next) {
        return new WorkflowNodeRun(id, runId, nodeId, nodeType, next, input, output,
                confirmationTaskId, confirmationVersion, confirmationSnapshotHash, retryCount,
                errorCode, startedAt, next == WorkflowNodeRunStatus.SUCCEEDED || next == WorkflowNodeRunStatus.FAILED
                || next == WorkflowNodeRunStatus.SKIPPED ? Instant.now() : finishedAt, createdAt, Instant.now());
    }

    public WorkflowNodeRun withOutput(Map<String, Object> nextOutput) {
        return new WorkflowNodeRun(id, runId, nodeId, nodeType, status, input, nextOutput,
                confirmationTaskId, confirmationVersion, confirmationSnapshotHash, retryCount,
                errorCode, startedAt, finishedAt, createdAt, Instant.now());
    }

    public WorkflowNodeRun withConfirmation(String taskId, int version, String snapshotHash) {
        return new WorkflowNodeRun(id, runId, nodeId, nodeType, WorkflowNodeRunStatus.WAITING_CONFIRMATION,
                input, output, taskId, version, snapshotHash, retryCount, errorCode, startedAt,
                finishedAt, createdAt, Instant.now());
    }

    public WorkflowNodeRun withError(String code) {
        return new WorkflowNodeRun(id, runId, nodeId, nodeType, WorkflowNodeRunStatus.FAILED,
                input, output, confirmationTaskId, confirmationVersion, confirmationSnapshotHash,
                retryCount, code, startedAt, Instant.now(), createdAt, Instant.now());
    }

    public WorkflowNodeRun reset(int nextRetryCount) {
        return new WorkflowNodeRun(id, runId, nodeId, nodeType, WorkflowNodeRunStatus.PENDING,
                input, output, confirmationTaskId, confirmationVersion, confirmationSnapshotHash,
                nextRetryCount, null, startedAt, finishedAt, createdAt, Instant.now());
    }

    public WorkflowNodeRun withRetryCount(int nextRetryCount) {
        return new WorkflowNodeRun(id, runId, nodeId, nodeType, status, input, output,
                confirmationTaskId, confirmationVersion, confirmationSnapshotHash, nextRetryCount,
                errorCode, startedAt, finishedAt, createdAt, Instant.now());
    }
}
