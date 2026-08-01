package com.manzhushaka.agent.runtime.store;

import com.manzhushaka.agent.runtime.routing.RouteSource;
import com.manzhushaka.agent.runtime.routing.RouteType;

import java.time.Instant;
import java.util.List;

public record RouteDecisionRecord(
        String id,
        String visitorId,
        String conversationId,
        String requestId,
        int routeSequence,
        String sourceAgentCode,
        String targetAgentCode,
        RouteType routeType,
        RouteSource routeSource,
        Double confidence,
        String reasonCode,
        List<String> candidateAgentCodes,
        Instant createdAt
) {
    public RouteDecisionRecord {
        candidateAgentCodes = candidateAgentCodes == null ? List.of() : List.copyOf(candidateAgentCodes);
    }
}
