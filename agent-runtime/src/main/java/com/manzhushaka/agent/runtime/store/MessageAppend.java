package com.manzhushaka.agent.runtime.store;

public record MessageAppend(
        String conversationId,
        String role,
        String content,
        String eventType,
        String agentCode,
        String actionCode
) {
}
