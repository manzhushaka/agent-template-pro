package com.manzhushaka.agent.runtime.workflow;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.Optional;

/** Durable boundary for workflow run facts. Implementations must persist each node checkpoint atomically. */
public interface WorkflowRunStore {
    WorkflowRun saveRun(WorkflowRun run);

    /** Atomic status transition; false when the run is not in the expected status. */
    boolean transitionRunStatus(String runId, WorkflowRunStatus expected, WorkflowRunStatus target);

    Optional<WorkflowRun> findRun(String runId);

    Optional<WorkflowRun> findRunForVisitor(String visitorRef, String runId);

    WorkflowRunPage listRuns(String keyword, String status, int page, int size);

    List<WorkflowRun> findStaleRunning(Instant now, Duration lease);

    WorkflowNodeRun saveNodeRun(WorkflowNodeRun nodeRun);

    Optional<WorkflowNodeRun> findNodeRun(String runId, String nodeId);

    List<WorkflowNodeRun> nodeRuns(String runId);

    void saveEvent(WorkflowEvent event);

    List<WorkflowEvent> events(String runId, long afterSequence, int limit);
}
