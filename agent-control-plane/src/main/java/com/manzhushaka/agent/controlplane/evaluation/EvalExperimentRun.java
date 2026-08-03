package com.manzhushaka.agent.controlplane.evaluation;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

public record EvalExperimentRun(
        String id,
        String experimentId,
        String caseId,
        String caseKey,
        String status,
        Boolean passed,
        BigDecimal score,
        String outputSummary,
        List<Map<String, Object>> evaluatorResults,
        String errorCode,
        int tokensUsed,
        long costMicros,
        Instant startedAt,
        Instant finishedAt,
        Instant createdAt,
        Instant updatedAt
) {
    public EvalExperimentRun {
        evaluatorResults = evaluatorResults == null ? List.of() : List.copyOf(evaluatorResults);
    }
}
