package com.manzhushaka.agent.chatapi.dto;

import com.manzhushaka.agent.runtime.chat.Conversation;

import java.time.Instant;

public record ConversationResponse(
        String id,
        String title,
        String activeAgentCode,
        String activeAgentName,
        long routingVersion,
        Instant createdAt,
        Instant lastMessageAt
) {
    public static ConversationResponse from(Conversation value, String activeAgentName) {
        return new ConversationResponse(
                value.id(), value.title(), value.activeAgentCode(), activeAgentName,
                value.routingVersion(), value.createdAt(), value.lastMessageAt()
        );
    }
}
