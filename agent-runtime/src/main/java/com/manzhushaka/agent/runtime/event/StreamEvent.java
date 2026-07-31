package com.manzhushaka.agent.runtime.event;

import java.time.Instant;
import java.util.Map;
public record StreamEvent(String type, String conversationId, String requestId, long sequence, Instant timestamp, Map<String,Object> payload) { }
