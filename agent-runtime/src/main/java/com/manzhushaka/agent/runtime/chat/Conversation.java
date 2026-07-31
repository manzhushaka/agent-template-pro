package com.manzhushaka.agent.runtime.chat;

import java.time.Instant;
public record Conversation(String id, String visitorId, String graphThreadId, String title, Instant createdAt, Instant lastMessageAt) { }
