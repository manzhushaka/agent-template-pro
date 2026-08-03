package com.manzhushaka.agent.controlplane.evaluation;

import java.math.BigDecimal;
import java.util.Map;

public record EvaluatorResult(
        String evaluatorCode,
        String evaluatorType,
        boolean passed,
        BigDecimal score,
        String reason,
        Map<String, Object> details
) {
    public EvaluatorResult {
        details = details == null ? Map.of() : Map.copyOf(details);
    }

    public static EvaluatorResult pass(String code, String type, String reason) {
        return new EvaluatorResult(code, type, true, BigDecimal.ONE, reason, Map.of());
    }

    public static EvaluatorResult fail(String code, String type, String reason) {
        return new EvaluatorResult(code, type, false, BigDecimal.ZERO, reason, Map.of());
    }
}
