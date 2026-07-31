package com.manzhushaka.agent.runtime.chat;

import java.time.Instant;
public record ChatMessage(long sequence, String role, String content, String eventType, Instant createdAt) { }
