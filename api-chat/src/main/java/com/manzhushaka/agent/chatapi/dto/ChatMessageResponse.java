package com.manzhushaka.agent.chatapi.dto;

import com.manzhushaka.agent.runtime.chat.ChatMessage;

import java.time.Instant;

public record ChatMessageResponse(
        long sequence,
        String role,
        String content,
        String eventType,
        String agentCode,
        String agentName,
        String actionCode,
        Instant createdAt
) {
    public static ChatMessageResponse from(ChatMessage message, String agentName) {
        return new ChatMessageResponse(
                message.sequence(), message.role(), message.content(), message.eventType(),
                message.agentCode(), agentName, message.actionCode(), message.createdAt()
        );
    }
}
