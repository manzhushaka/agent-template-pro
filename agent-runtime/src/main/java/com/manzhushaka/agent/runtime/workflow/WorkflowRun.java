package com.manzhushaka.agent.runtime.workflow;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/** Durable workflow run fact. Every node completion persists a new state so pause/resume/restart is deterministic. */
public record WorkflowRun(
        String id,
        String workflowId,
        String workflowVersionId,
        String code,
        String dslJson,
        String graphThreadId,
        WorkflowRunStatus status,
        String visitorRef,
        String requestId,
        Map<String, Object> variables,
        List<String> visitedNodeIds,
        List<String> visitedEdgeKeys,
        List<String> pendingEdgeKeys,
        String currentNodeId,
        String errorCode,
        String claimOwner,
        Instant claimLeaseUntil,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public WorkflowRun {
        variables = Map.copyOf(variables == null ? Map.of() : variables);
        visitedNodeIds = List.copyOf(visitedNodeIds == null ? List.of() : visitedNodeIds);
        visitedEdgeKeys = List.copyOf(visitedEdgeKeys == null ? List.of() : visitedEdgeKeys);
        pendingEdgeKeys = List.copyOf(pendingEdgeKeys == null ? List.of() : pendingEdgeKeys);
    }

    public WorkflowRun withState(
            WorkflowRunStatus nextStatus,
            Map<String, Object> nextVariables,
            List<String> nextVisitedNodes,
            List<String> nextVisitedEdges,
            List<String> nextPendingEdges,
            String nextCurrentNodeId,
            String nextErrorCode
    ) {
        Instant now = Instant.now();
        Instant nextFinished = nextStatus == WorkflowRunStatus.SUCCEEDED
                || nextStatus == WorkflowRunStatus.FAILED
                || nextStatus == WorkflowRunStatus.STOPPED ? now : finishedAt;
        return new WorkflowRun(id, workflowId, workflowVersionId, code, dslJson, graphThreadId, nextStatus,
                visitorRef, requestId, nextVariables, nextVisitedNodes, nextVisitedEdges,
                nextPendingEdges, nextCurrentNodeId, nextErrorCode, claimOwner, claimLeaseUntil,
                startedAt, nextFinished, createdAt, now);
    }
}
