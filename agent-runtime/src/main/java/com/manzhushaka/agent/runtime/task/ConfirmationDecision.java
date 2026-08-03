package com.manzhushaka.agent.runtime.task;

public enum ConfirmationDecision {
    CONFIRMED(TaskStatus.DISPATCHED),
    REJECTED(TaskStatus.CANCELLED),
    EXPIRED(TaskStatus.EXPIRED);

    private final TaskStatus targetStatus;

    ConfirmationDecision(TaskStatus targetStatus) {
        this.targetStatus = targetStatus;
    }

    public TaskStatus targetStatus() {
        return targetStatus;
    }
}
