package com.manzhushaka.agent.runtime.model;

import java.util.Map;

/** Structured candidate produced by a model before runtime authorization and validation. */
public record ModelActionDecision(String actionCode, Map<String, Object> input) {
    public ModelActionDecision {
        input = input == null ? Map.of() : Map.copyOf(input);
    }
}
