package com.manzhushaka.agent.runtime.recovery;

import com.manzhushaka.agent.runtime.task.AgentTask;

/** Reliable provider query keyed by the task idempotency key or an external reference. */
public interface TaskRecoveryProbe {
    boolean supports(String actionCode);

    TaskRecoveryResult query(AgentTask task);
}
