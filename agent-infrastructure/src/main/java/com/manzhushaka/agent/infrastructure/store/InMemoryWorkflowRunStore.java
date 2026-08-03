package com.manzhushaka.agent.infrastructure.store;

import com.manzhushaka.agent.runtime.workflow.WorkflowEvent;
import com.manzhushaka.agent.runtime.workflow.WorkflowNodeRun;
import com.manzhushaka.agent.runtime.workflow.WorkflowRun;
import com.manzhushaka.agent.runtime.workflow.WorkflowRunPage;
import com.manzhushaka.agent.runtime.workflow.WorkflowRunStatus;
import com.manzhushaka.agent.runtime.workflow.WorkflowRunStore;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Repository;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentSkipListMap;
import java.util.concurrent.atomic.AtomicLong;

/** In-memory workflow run store used by the default demo profile. */
@Repository
@Profile("!runtime-jdbc")
public class InMemoryWorkflowRunStore implements WorkflowRunStore {
    private final Map<String, WorkflowRun> runs = new ConcurrentHashMap<>();
    private final Map<String, WorkflowNodeRun> nodeRuns = new ConcurrentHashMap<>();
    private final Map<String, ConcurrentSkipListMap<Long, WorkflowEvent>> events = new ConcurrentHashMap<>();
    private final AtomicLong eventSequence = new AtomicLong();

    @Override
    public synchronized WorkflowRun saveRun(WorkflowRun run) {
        runs.put(run.id(), run);
        return run;
    }

    @Override
    public boolean transitionRunStatus(String runId, WorkflowRunStatus expected, WorkflowRunStatus target) {
        synchronized (this) {
            WorkflowRun current = runs.get(runId);
            if (current == null || current.status() != expected) {
                return false;
            }
            runs.put(runId, current.withState(
                    target, current.variables(), current.visitedNodeIds(), current.visitedEdgeKeys(),
                    current.pendingEdgeKeys(), current.currentNodeId(), current.errorCode()
            ));
            return true;
        }
    }

    @Override
    public Optional<WorkflowRun> findRun(String runId) {
        return Optional.ofNullable(runs.get(runId));
    }

    @Override
    public Optional<WorkflowRun> findRunForVisitor(String visitorRef, String runId) {
        return findRun(runId).filter(run -> run.visitorRef().equals(visitorRef));
    }

    @Override
    public WorkflowRunPage listRuns(String keyword, String status, int page, int size) {
        List<WorkflowRun> all = runs.values().stream()
                .filter(run -> status == null || status.isBlank() || run.status().name().equalsIgnoreCase(status))
                .filter(run -> keyword == null || keyword.isBlank()
                        || run.code().toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT))
                        || run.id().toLowerCase(Locale.ROOT).contains(keyword.toLowerCase(Locale.ROOT)))
                .sorted(Comparator.comparing(WorkflowRun::createdAt).reversed())
                .toList();
        int from = Math.max(0, (page - 1) * size);
        int to = Math.min(all.size(), from + size);
        return new WorkflowRunPage(from >= all.size() ? List.of() : all.subList(from, to), all.size());
    }

    @Override
    public List<WorkflowRun> findStaleRunning(Instant now, Duration lease) {
        return runs.values().stream()
                .filter(run -> run.status() == WorkflowRunStatus.RUNNING)
                .filter(run -> run.updatedAt().isBefore(now.minus(lease)))
                .toList();
    }

    @Override
    public WorkflowNodeRun saveNodeRun(WorkflowNodeRun nodeRun) {
        nodeRuns.put(nodeRun.runId() + ":" + nodeRun.nodeId(), nodeRun);
        return nodeRun;
    }

    @Override
    public Optional<WorkflowNodeRun> findNodeRun(String runId, String nodeId) {
        return Optional.ofNullable(nodeRuns.get(runId + ":" + nodeId));
    }

    @Override
    public List<WorkflowNodeRun> nodeRuns(String runId) {
        return nodeRuns.values().stream()
                .filter(nodeRun -> nodeRun.runId().equals(runId))
                .sorted(Comparator.comparing(WorkflowNodeRun::startedAt))
                .toList();
    }

    @Override
    public void saveEvent(WorkflowEvent event) {
        events.computeIfAbsent(event.runId(), key -> new ConcurrentSkipListMap<>())
                .put(event.sequence(), event);
    }

    @Override
    public List<WorkflowEvent> events(String runId, long afterSequence, int limit) {
        ConcurrentSkipListMap<Long, WorkflowEvent> map = events.get(runId);
        if (map == null) {
            return List.of();
        }
        List<WorkflowEvent> result = new ArrayList<>();
        for (WorkflowEvent event : map.tailMap(afterSequence, false).values()) {
            result.add(event);
            if (result.size() >= limit) {
                break;
            }
        }
        return List.copyOf(result);
    }
}
