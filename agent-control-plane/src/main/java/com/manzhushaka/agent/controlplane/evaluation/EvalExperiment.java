package com.manzhushaka.agent.controlplane.evaluation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public record EvalExperiment(
        String id,
        String code,
        String displayName,
        String datasetId,
        String datasetVersionId,
        String agentApplicationId,
        String agentVersionId,
        List<String> evaluatorVersionIds,
        String status,
        String runKey,
        int totalCases,
        int completedCases,
        int passedCases,
        int failedCases,
        int errorCases,
        long costMicros,
        BigDecimal thresholdPassRate,
        BigDecimal passRate,
        String claimOwner,
        Instant claimLeaseUntil,
        Instant startedAt,
        Instant finishedAt,
        String createdBy,
        Instant createdAt,
        Instant updatedAt
) {
    public EvalExperiment {
        evaluatorVersionIds = evaluatorVersionIds == null ? List.of() : List.copyOf(evaluatorVersionIds);
    }
}
