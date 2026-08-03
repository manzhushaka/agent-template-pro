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
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/** Advances a task after an adapter has authenticated and de-duplicated an external callback. */
@Service
public class TrustedTaskResultService {
    private final RuntimeStore store;

    public TrustedTaskResultService(RuntimeStore store) {
        this.store = store;
    }

    public AgentTask apply(
            String visitorId,
            String taskId,
            String requestId,
            String externalRef,
            TaskStatus terminalStatus,
            String resultSummary,
            String errorCode,
            Instant receivedAt
    ) {
        if (terminalStatus != TaskStatus.SUCCEEDED && terminalStatus != TaskStatus.FAILED) {
            throw new IllegalArgumentException("callback status must be terminal");
        }
        AgentTask task = store.findTask(visitorId, taskId).orElseThrow(
                () -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "任务不存在或不属于当前访客")
        );
        if (task.externalRef() != null && !Objects.equals(task.externalRef(), externalRef)) {
            throw new BusinessException(ErrorCode.TASK_CONFIRMATION_CONFLICT, "外部业务引用与任务不匹配");
        }
        if (task.status() == terminalStatus) {
            return task;
        }
        if (task.status() != TaskStatus.DISPATCHED
                && task.status() != TaskStatus.WAITING_EXTERNAL_RESULT
                && task.status() != TaskStatus.UNKNOWN) {
            throw new BusinessException(ErrorCode.TASK_CONFIRMATION_CONFLICT, "任务状态不允许外部结果推进");
        }
        TaskTransition transition = terminalStatus == TaskStatus.SUCCEEDED
                ? TaskTransition.completed(externalRef, resultSummary)
                : new TaskTransition(externalRef, null, errorCode, null, null, 0);
        AgentTask updated = store.transitionTask(
                visitorId, taskId, task.status(), task.version(), terminalStatus, transition
        ).orElseThrow(() -> new BusinessException(ErrorCode.TASK_CONFIRMATION_CONFLICT, "任务状态已变化"));
        store.saveAudit(new AuditRecord(
                "aud_" + UUID.randomUUID(), visitorId, taskId, requestId, "TRUSTED_EXTERNAL_RESULT",
                "EXTERNAL_SYSTEM", Map.of("status", terminalStatus.name()), receivedAt
        ));
        return updated;
    }
}
