package com.manzhushaka.agent.controlplane.evaluation;

import java.time.Instant;
import java.util.Map;

/** A single evaluation case. Input/expected content is stored at rest but masked in every API view/export. */
public record EvalCase(
        String id,
        String datasetId,
        String datasetVersionId,
        String caseKey,
        String category,
        Map<String, Object> input,
        Map<String, Object> expected,
        Map<String, Object> tags,
        String source,
        String traceId,
        String createdBy,
        Instant createdAt
) {
    public EvalCase {
        input = input == null ? Map.of() : Map.copyOf(input);
        expected = expected == null ? Map.of() : Map.copyOf(expected);
        tags = tags == null ? Map.of() : Map.copyOf(tags);
    }
}
