package com.manzhushaka.agent.runtime.task;

import java.util.EnumSet;
import java.util.Set;

public enum TaskStatus {
    CREATED,
    COLLECTING_INPUT,
    WAITING_CONFIRMATION,
    DISPATCHED,
    WAITING_EXTERNAL_RESULT,
    SUCCEEDED,
    FAILED,
    CANCELLED,
    EXPIRED,
    UNKNOWN,
    MANUAL;

    public boolean canTransitionTo(TaskStatus target) {
        return allowedTargets().contains(target);
    }

    private Set<TaskStatus> allowedTargets() {
        return switch (this) {
            case CREATED -> EnumSet.of(COLLECTING_INPUT, WAITING_CONFIRMATION, CANCELLED, EXPIRED);
            case COLLECTING_INPUT -> EnumSet.of(WAITING_CONFIRMATION, CANCELLED, EXPIRED);
            case WAITING_CONFIRMATION -> EnumSet.of(DISPATCHED, CANCELLED, EXPIRED);
            case DISPATCHED -> EnumSet.of(WAITING_EXTERNAL_RESULT, SUCCEEDED, FAILED, UNKNOWN, MANUAL);
            case WAITING_EXTERNAL_RESULT -> EnumSet.of(WAITING_EXTERNAL_RESULT, SUCCEEDED, FAILED, UNKNOWN, MANUAL);
            case UNKNOWN -> EnumSet.of(UNKNOWN, SUCCEEDED, FAILED, MANUAL);
            case FAILED -> EnumSet.of(DISPATCHED, MANUAL);
            case SUCCEEDED, CANCELLED, EXPIRED, MANUAL -> EnumSet.noneOf(TaskStatus.class);
        };
    }
}
