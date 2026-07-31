package com.manzhushaka.agent.chatapi.dto;

import com.manzhushaka.agent.runtime.task.AgentTask;
import java.time.Instant;
public record TaskResponse(String id, String actionCode, String status, String externalRef, Instant createdAt) {
    public static TaskResponse from(AgentTask task) { return new TaskResponse(task.id(), task.actionCode(), task.status().name(), task.externalRef(), task.createdAt()); }
}
