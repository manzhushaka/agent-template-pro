package com.manzhushaka.agent.chatapi.dto;

import com.manzhushaka.agent.runtime.chat.Conversation;
import java.time.Instant;
public record ConversationResponse(String id, String title, Instant createdAt, Instant lastMessageAt) {
    public static ConversationResponse from(Conversation value) { return new ConversationResponse(value.id(), value.title(), value.createdAt(), value.lastMessageAt()); }
}
