package com.manzhushaka.agent.runtime.chat;

import java.time.Instant;

public record ChatMessage(
        long sequence,
        String role,
        String content,
        String eventType,
        String agentCode,
        String actionCode,
        Instant createdAt
) {
    public ChatMessage(long sequence, String role, String content, String eventType, Instant createdAt) {
        this(sequence, role, content, eventType, null, null, createdAt);
    }
}
