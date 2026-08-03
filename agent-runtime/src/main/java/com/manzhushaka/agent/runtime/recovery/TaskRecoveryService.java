package com.manzhushaka.agent.runtime.recovery;

import com.manzhushaka.agent.common.error.BusinessException;
import com.manzhushaka.agent.common.error.ErrorCode;
import com.manzhushaka.agent.runtime.store.AuditRecord;
import com.manzhushaka.agent.runtime.store.RuntimeStore;
import com.manzhushaka.agent.runtime.task.AgentTask;
import com.manzhushaka.agent.runtime.task.TaskStatus;
import com.manzhushaka.agent.runtime.task.TaskTransition;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Recovers tasks only through reliable provider queries; it never repeats the original write. */
@Service
public class TaskRecoveryService {
    private final RuntimeStore store;
    private final List<TaskRecoveryProbe> probes;

    public TaskRecoveryService(RuntimeStore store, List<TaskRecoveryProbe> probes) {
        this.store = store;
        this.probes = List.copyOf(probes);
    }

    public List<AgentTask> recoverDue(Instant now, int limit) {
        return store.findRecoverableTasks(now, Math.clamp(limit, 1, 200)).stream()
                .map(task -> recover(task.visitorId(), task.id(), "recovery-" + UUID.randomUUID(), now))
                .toList();
    }

    public AgentTask recover(String visitorId, String taskId, String requestId, Instant now) {
        AgentTask task = store.findTask(visitorId, taskId).orElseThrow(
                () -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在或不属于当前访客")
        );
        if (!isRecoverable(task.status())) {
            return task;
        }
        TaskRecoveryResult result = probes.stream()
                .filter(probe -> probe.supports(task.actionCode()))
                .findFirst()
                .map(probe -> probe.query(task))
                .orElseGet(() -> TaskRecoveryResult.unknown(now.plus(5, ChronoUnit.MINUTES)));
        TaskStatus target = targetStatus(task.status(), result.outcome());
        TaskTransition transition = transition(result, now);
        AgentTask updated = store.transitionTask(
                visitorId, taskId, task.status(), task.version(), target, transition
        ).orElseGet(() -> store.findTask(visitorId, taskId).orElseThrow());
        store.saveAudit(new AuditRecord(
                "aud_" + UUID.randomUUID(), visitorId, taskId, requestId, "TASK_RECOVERY_" + result.outcome(),
                "SYSTEM", Map.of("fromStatus", task.status().name(), "toStatus", updated.status().name()), now
        ));
        return updated;
    }

    private boolean isRecoverable(TaskStatus status) {
        return status == TaskStatus.DISPATCHED
                || status == TaskStatus.WAITING_EXTERNAL_RESULT
                || status == TaskStatus.UNKNOWN;
    }

    private TaskStatus targetStatus(TaskStatus current, TaskRecoveryOutcome outcome) {
        return switch (outcome) {
            case SUCCEEDED -> TaskStatus.SUCCEEDED;
            case FAILED -> TaskStatus.FAILED;
            case STILL_PENDING -> current == TaskStatus.DISPATCHED ? TaskStatus.WAITING_EXTERNAL_RESULT : current;
            case UNKNOWN -> TaskStatus.UNKNOWN;
        };
    }

    private TaskTransition transition(TaskRecoveryResult result, Instant now) {
        return switch (result.outcome()) {
            case SUCCEEDED -> TaskTransition.completed(result.externalRef(), result.resultSummary());
            case FAILED -> new TaskTransition(result.externalRef(), null, result.errorCode(), null, null, 1);
            case STILL_PENDING, UNKNOWN -> new TaskTransition(
                    result.externalRef(), null, result.errorCode(), null,
                    result.retryAt() == null ? now.plus(5, ChronoUnit.MINUTES) : result.retryAt(), 1
            );
        };
    }
}
