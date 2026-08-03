package com.manzhushaka.agent.controlplane.evaluation;

import java.util.Map;

public record EvaluatorContext(
        String evaluatorVersionId,
        String evaluatorCode,
        String evaluatorType,
        Map<String, Object> config,
        EvalCase evalCase,
        EvaluationRunOutcome outcome
) {
    public EvaluatorContext {
        config = config == null ? Map.of() : Map.copyOf(config);
    }
}
