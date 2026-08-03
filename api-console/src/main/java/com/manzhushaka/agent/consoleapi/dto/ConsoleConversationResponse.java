package com.manzhushaka.agent.consoleapi.dto;

import java.time.Instant;

public record ConsoleConversationResponse(
        String id,
        String visitorRef,
        String title,
        String activeAgentCode,
        String activeAgentName,
        long routingVersion,
        Instant createdAt,
        Instant lastMessageAt
) {
}
