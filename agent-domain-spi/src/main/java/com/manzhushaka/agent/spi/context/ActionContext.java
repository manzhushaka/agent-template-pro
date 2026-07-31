package com.manzhushaka.agent.spi.context;
public record ActionContext(String visitorId, String conversationId, String requestId, String idempotencyKey) { }
