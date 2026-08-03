package com.manzhushaka.agent.consoleapi.dto;

import java.util.Map;

public record ObservabilityOverviewResponse(
        long totalTraces,
        long totalSpans,
        Map<String, Long> spanTypeDistribution,
        Map<String, Long> spanStatusDistribution,
        ModelMetricsResponse model,
        ToolMetricsResponse tool,
        TaskMetricsResponse task
) {
    public record ModelMetricsResponse(
            int calls,
            long avgLatencyMs,
            long p95LatencyMs,
            long totalTokens,
            long errorCount,
            long timeoutCount
    ) {
    }

    public record ToolMetricsResponse(
            int calls,
            long avgLatencyMs,
            long errorCount,
            long unknownCount
    ) {
    }

    public record TaskMetricsResponse(
            Map<String, Long> statusDistribution,
            long confirmationCreated,
            long confirmationConfirmed,
            long confirmationRejected,
            double confirmationRate,
            long unknownTasks,
            long timeoutTasks
    ) {
    }
}
