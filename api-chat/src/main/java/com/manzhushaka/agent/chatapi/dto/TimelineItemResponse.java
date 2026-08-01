package com.manzhushaka.agent.chatapi.dto;

import com.manzhushaka.agent.runtime.store.TimelineItem;

import java.time.Instant;
import java.util.Map;

public record TimelineItemResponse(
        long sequence,
        String kind,
        String role,
        String content,
        String eventType,
        String requestId,
        String agentCode,
        String agentName,
        String actionCode,
        Instant createdAt,
        Map<String, Object> payload
) {
    public static TimelineItemResponse from(TimelineItem item, String agentName) {
        return new TimelineItemResponse(
                item.sequence(), item.kind(), item.role(), item.content(), item.eventType(), item.requestId(),
                item.agentCode(), agentName, item.actionCode(), item.createdAt(), item.payload()
        );
    }
}
