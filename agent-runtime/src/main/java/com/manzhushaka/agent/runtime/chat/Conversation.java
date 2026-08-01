package com.manzhushaka.agent.runtime.chat;

import java.time.Instant;

public record Conversation(
        String id,
        String visitorId,
        String graphThreadId,
        String title,
        String activeAgentCode,
        long routingVersion,
        Instant createdAt,
        Instant lastMessageAt
) {
    public Conversation(
            String id,
            String visitorId,
            String graphThreadId,
            String title,
            Instant createdAt,
            Instant lastMessageAt
    ) {
        this(id, visitorId, graphThreadId, title, null, 0, createdAt, lastMessageAt);
    }
}
