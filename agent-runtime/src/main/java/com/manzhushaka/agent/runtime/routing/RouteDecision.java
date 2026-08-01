package com.manzhushaka.agent.runtime.routing;

import java.util.List;

public record RouteDecision(
        RouteType type,
        String targetAgentCode,
        double confidence,
        RouteSource source,
        String reasonCode,
        List<String> clarificationCandidates
) {
    public RouteDecision {
        clarificationCandidates = clarificationCandidates == null
                ? List.of()
                : List.copyOf(clarificationCandidates);
    }
}
