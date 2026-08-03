package com.manzhushaka.agent.consoleapi.dto;

import java.time.Instant;

public record ConsoleEventResponse(
        String type,
        String conversationId,
        String requestId,
        long sequence,
        Instant timestamp,
        String taskId,
        String status,
        String actionCode,
        String agentCode
) {
}
