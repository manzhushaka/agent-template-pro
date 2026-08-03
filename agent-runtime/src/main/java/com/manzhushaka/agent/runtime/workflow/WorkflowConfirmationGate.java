package com.manzhushaka.agent.runtime.workflow;

import com.manzhushaka.agent.common.error.BusinessException;
import com.manzhushaka.agent.common.error.ErrorCode;
import com.manzhushaka.agent.runtime.store.RuntimeStore;
import com.manzhushaka.agent.runtime.task.AgentTask;
import com.manzhushaka.agent.runtime.task.ConfirmationDecision;
import com.manzhushaka.agent.runtime.task.ConfirmationSnapshotHasher;
import com.manzhushaka.agent.runtime.task.TaskStatus;
import com.manzhushaka.agent.runtime.task.TaskTransition;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

/**
 * Workflow write gate. Reuses the same AgentTask + confirmation snapshot hashing as chat: a write
 * node creates a WAITING_CONFIRMATION task owned by the run visitor, and the write executes only
 * after a versioned confirmation decision succeeds.
 */
@Component
public class WorkflowConfirmationGate {
    private static final String WORKFLOW_DOMAIN = "workflow";

    private final RuntimeStore store;
    private final ConfirmationSnapshotHasher snapshotHasher;

    public WorkflowConfirmationGate(RuntimeStore store, ConfirmationSnapshotHasher snapshotHasher) {
        this.store = store;
        this.snapshotHasher = snapshotHasher;
    }

    public WorkflowTaskPreparation prepare(
            String visitorRef,
            String runId,
            String nodeId,
            String requestId,
            String actionCode,
            Map<String, Object> input
    ) {
        AgentTask task = new AgentTask(
                "tsk_" + UUID.randomUUID(),
                visitorRef,
                runId,
                WORKFLOW_DOMAIN,
                actionCode,
                "wfl_" + runId + "_" + nodeId,
                input
        );
        String snapshotHash = snapshotHasher.hash(actionCode, input);
        task.prepareConfirmation(1, snapshotHash, Instant.now().plus(15, ChronoUnit.MINUTES));
        AgentTask saved = store.saveTask(task);
        if (!saved.visitorId().equals(visitorRef) || saved.status() != TaskStatus.WAITING_CONFIRMATION) {
            throw new BusinessException(ErrorCode.REQUEST_DUPLICATED, "工作流确认任务创建冲突");
        }
        return new WorkflowTaskPreparation(saved.id(), saved.confirmationVersion(), saved.confirmationSnapshotHash());
    }

    /** Decides a pending confirmation; returns the task when CONFIRMED, otherwise the rejected task. */
    public AgentTask decide(
            String visitorRef,
            String taskId,
            int confirmationVersion,
            String snapshotHash,
            ConfirmationDecision decision,
            String requestId
    ) {
        AgentTask task = store.findTask(visitorRef, taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "确认任务不存在"));
        if (task.status() != TaskStatus.WAITING_CONFIRMATION) {
            throw new BusinessException(ErrorCode.CONFLICT, "确认任务状态不允许操作: " + task.status());
        }
        return store.decideConfirmation(
                visitorRef,
                taskId,
                confirmationVersion,
                task.version(),
                snapshotHash,
                decision,
                requestId,
                Instant.now(),
                Instant.now().plus(30, ChronoUnit.SECONDS)
        ).orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "确认信息已过期或已变更，请重新发起"));
    }

    /** Marks a confirmed task as executed after the underlying write completed. */
    public AgentTask completeConfirmed(String visitorRef, String taskId, long taskVersion, String requestId) {
        return store.transitionTask(
                visitorRef,
                taskId,
                TaskStatus.DISPATCHED,
                taskVersion,
                TaskStatus.SUCCEEDED,
                TaskTransition.completed(null, null)
        ).orElseThrow(() -> new BusinessException(ErrorCode.CONFLICT, "工作流任务已完成状态无法推进"));
    }

    public void failConfirmed(String visitorRef, String taskId, long taskVersion, String errorCode) {
        store.transitionTask(
                visitorRef,
                taskId,
                TaskStatus.DISPATCHED,
                taskVersion,
                TaskStatus.FAILED,
                TaskTransition.failed(errorCode)
        );
    }

    /** Returns the confirmed (DISPATCHED) task so a handler can execute the underlying operation. */
    public AgentTask confirmedTask(String visitorRef, String taskId) {
        AgentTask task = store.findTask(visitorRef, taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "确认任务不存在"));
        if (task.status() != TaskStatus.DISPATCHED) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务尚未确认或已推进: " + task.status());
        }
        return task;
    }

    /** Returns a still-waiting confirmation task (used to capture the snapshot hash for the node run). */
    public AgentTask pendingTask(String visitorRef, String taskId) {
        AgentTask task = store.findTask(visitorRef, taskId)
                .orElseThrow(() -> new BusinessException(ErrorCode.RESOURCE_NOT_FOUND, "确认任务不存在"));
        if (task.status() != TaskStatus.WAITING_CONFIRMATION) {
            throw new BusinessException(ErrorCode.CONFLICT, "任务已推进，无法取得确认快照: " + task.status());
        }
        return task;
    }

    public void cancelPending(String visitorRef, String taskId) {
        AgentTask task = store.findTask(visitorRef, taskId).orElse(null);
        if (task != null && task.status() == TaskStatus.WAITING_CONFIRMATION) {
            store.transitionTask(
                    visitorRef,
                    taskId,
                    TaskStatus.WAITING_CONFIRMATION,
                    task.version(),
                    TaskStatus.CANCELLED,
                    TaskTransition.failed("WORKFLOW_STOPPED")
            );
        }
    }
}
