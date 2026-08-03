package com.manzhushaka.agent.consoleapi.dto;

public record ConsoleOverviewResponse(
        String runtimeStatus,
        String storageMode,
        long conversationTotal,
        long taskTotal,
        long activeTasks,
        long unknownTasks,
        long agentTotal
) {
}
