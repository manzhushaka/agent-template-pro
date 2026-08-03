package com.manzhushaka.agent.controlplane.evaluation;

import java.util.Map;

public record EvaluationExecutionContext(
        String experimentId,
        String experimentCode,
        String caseId,
        String caseKey,
        String appCode,
        String agentVersionId,
        String visitorId,
        String requestId,
        Map<String, Object> input
) {
    public EvaluationExecutionContext {
        input = input == null ? Map.of() : Map.copyOf(input);
    }
}
